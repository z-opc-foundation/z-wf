package com.zifang.z.wf.core.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinition;
import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.model.WfActivityInstance;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.view.WfActivityInstanceView;
import com.zifang.z.wf.core.view.WfTransitionInstanceView;

/**
 * 活动实例树 —— "这条单现在走到哪了，并发分支在哪，各分支停在哪一步"。
 *
 * <p>对应 Camunda 的 {@code RuntimeService#getActivityInstance}。
 * 它与本仓已有的 {@code WfHistoryService#getProcessOverview} 里的 {@code trail}
 * <b>不是一回事，也不互相推导</b>：
 * <ul>
 *   <li>{@code trail} 是<b>时间序</b>的事实记录，回答"发生过什么"。</li>
 *   <li>本树是<b>结构</b>，回答"现在有哪几条并发分支、每条停在哪"。</li>
 * </ul>
 * 两者给同一批事实、两种切法。开两条入口而不是合成一条，是因为它们的查询方式不同：
 * trail 读历史表，本树读执行树。并行分支一旦跑起来，
 * trail 里的行在时间上是交错的、看不出谁是谁的分支 —— 而交错的历史行
 * <b>推不出</b>层级，硬推会在并行分支上猜错。猜出来的层级比扁平轨迹更坏：它看起来可信。
 *
 * <p><b>树的形状由执行树决定，不由历史行决定</b>：
 * 本仓一个 token 连续穿过多个节点（{@code leave} 是把同一个 token 的 activityId 往前挪），
 * 所以 token 的身份标识的是"一条并发分支"，不是"一个节点访问"。
 * 于是：树给<b>并发结构</b>，每个节点内的 {@code childTransitionInstances}
 * 给<b>这条分支走过的顺序</b>。详见 {@link WfActivityInstanceView} 的类注释。
 *
 * <p><b>它是纯读的</b>：不推进流程、不投递事件、不改任何状态。
 *
 * @author zifang
 */
public class WfActivityInstanceService {

    private static final Logger log = LoggerFactory.getLogger(WfActivityInstanceService.class);

    /**
     * 一次最多参与组树的 token 数。
     *
     * <p>token 数 = 并发分支数 × 分支长度之外的东西，正常审批单是几条到几十条。
     * 超过这个数说明流程被设计成了近乎指数分叉的形状（比如循环里并行网关套并行网关），
     * 那时候一棵完整的树前端也画不动，报"结果可能被截断"比给一棵跑不动的树有用。
     */
    private static final int MAX_EXECUTIONS = 2000;

    /**
     * 一次最多挂进树的历史行数。
     *
     * <p>与 {@code WfHistoryService} 的查询上限同量级：它护的是内存，
     * 不是数据库 —— 树是一次性全量返回的，没有分页可言。
     */
    private static final int MAX_HISTORY = 5000;

    private final WfPersistence persistence;

    private final WfRepositoryService repositoryService;

    public WfActivityInstanceService(WfPersistence persistence,
                                    WfRepositoryService repositoryService) {
        this.persistence = persistence;
        this.repositoryService = repositoryService;
    }

    /**
     * 活动实例树。
     *
     * @param processInstanceId 流程实例 id
     * @return 根节点；流程没有任何 token 时也返回根节点（children 为空），
     *         但 {@code terminal} 会告诉调用方"这是结束了还是压根没跑起来"
     * @throws WfEngineException 实例不存在，或规模超限
     */
    public WfActivityInstanceView getActivityInstance(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.trim().isEmpty()) {
            throw new WfEngineException("流程实例 id 不能为空");
        }
        WfProcessInstance instance = persistence.findProcessInstance(processInstanceId);
        if (instance == null) {
            // 不返回一棵空树：调用方拿到空树无法区分「这单没跑起来」「这单被清过历史」
            // 与「这单真的没有分支」—— 三者的处置完全不同。
            // 而令牌是跟着历史一起清的（本轮把 JDBC 那边漏删的补齐了），
            // 所以"实例没了"就必然意味着"树也没了"，只有一种成因要指路。
            throw new WfEngineException("流程实例不存在: " + processInstanceId
                    + "。令牌与历史一起被清理（DELETE /api/wf/history/cleanup），"
                    + "所以实例一旦查不到，活动实例树也就没有了");
        }

        List<WfExecution> executions = persistence.findExecutionsByProcessInstance(
                processInstanceId);
        if (executions.size() > MAX_EXECUTIONS) {
            throw new WfEngineException("流程 " + processInstanceId + " 有 " + executions.size()
                    + " 条执行令牌，超过上限 " + MAX_EXECUTIONS
                    + "，树的规模已经不适合一次性返回");
        }

        WfActivityInstanceView root = rootOf(instance);

        // token 可能在库里被外力删过（并发删除、迁移到别的实例），parentId 指向的
        // 那条不在结果里。挂不到任何父节点上的 token 不静默丢弃 ——
        // 丢弃会让"这单有 3 条分支"变成 2 条，而且没有任何报错。
        Map<String, WfActivityInstanceView> nodes = buildNodes(instance, executions);
        List<WfActivityInstanceView> orphans = new ArrayList<>();
        for (WfExecution execution : executions) {
            WfActivityInstanceView node = nodes.get(execution.getId());
            String parentId = execution.getParentId();
            WfActivityInstanceView parent = parentId == null ? null : nodes.get(parentId);
            if (parent == null) {
                if (parentId != null) {
                    orphans.add(node);
                } else {
                    // 没有 parentId 的就是顶层令牌，直接挂根。
                    // 父指针必须一并写上：children 说"你是根的孩子"、
                    // parentActivityInstanceId 却是 null，调用方没法确定该信哪一个，
                    // 而往下走树时两种走法会给出不同结果。
                    node.setParentActivityInstanceId(root.getId());
                    root.getChildActivityInstances().add(node);
                }
                continue;
            }
            node.setParentActivityInstanceId(parent.getId());
            parent.getChildActivityInstances().add(node);
        }
        if (!orphans.isEmpty()) {
            throw new WfEngineException("流程 " + processInstanceId + " 的执行令牌树不完整："
                    + orphans.size() + " 条令牌的父节点在库里不存在（"
                    + idsOf(orphans) + "）。这通常意味着 token 被并发删掉了，"
                    + "继续拼出来的树会少掉分支 —— 宁可报错");
        }

        markConcurrent(root, countActive(executions));
        attachTransitions(processInstanceId, nodes.values());
        return root;
    }

    /** 还没结束的 token 数 —— "这单现在有几条分支在跑"的唯一来源。 */
    private int countActive(List<WfExecution> executions) {
        int active = 0;
        for (WfExecution execution : executions) {
            if (!execution.isEnded()) {
                active++;
            }
        }
        return active;
    }

    private WfActivityInstanceView rootOf(WfProcessInstance instance) {
        WfActivityInstanceView root = new WfActivityInstanceView();
        root.setId("process:" + instance.getId());
        root.setParentActivityInstanceId(null);
        root.setProcessInstanceId(instance.getId());
        root.setProcessDefinitionKey(instance.getDefinitionKey());
        root.setProcessDefinitionVersion(String.valueOf(instance.getDefinitionVersion()));
        // 根不是一条 token，所以 executionId / branchId / enteredTime 全部留空。
        // 填一个"看起来像"的值比留空更坏：调用方会拿它去 join 历史行，而 join 不上。
        root.setActivityId(null);
        root.setActivityName(instance.getDefinitionKey());
        root.setActivityType("process");
        root.setExecutionState(instance.getStatus() == null ? null : instance.getStatus().name());
        return root;
    }

    private Map<String, WfActivityInstanceView> buildNodes(WfProcessInstance instance,
                                                           List<WfExecution> executions) {
        Map<String, WfNode> nodeIndex = nodeIndexOf(instance);
        Map<String, WfActivityInstanceView> nodes = new LinkedHashMap<>();
        for (WfExecution execution : executions) {
            WfNode node = nodeIndex.get(execution.getActivityId());
            WfActivityInstanceView view = new WfActivityInstanceView(execution,
                    node == null ? null : node.getName(),
                    node == null ? null : node.getType().bpmnName());
            view.setProcessDefinitionKey(instance.getDefinitionKey());
            view.setProcessDefinitionVersion(String.valueOf(instance.getDefinitionVersion()));
            nodes.put(execution.getId(), view);
        }
        return nodes;
    }

    /**
     * 节点索引：key/version 下全部节点，值含名字与类型。
     *
     * <p><b>定义读不出来就让异常往上抛，不返回空索引。</b>
     * 空索引不会报错，只会让整棵树每个节点的名字与类型都变成 null ——
     * 而"这棵树里所有节点都没有名字"这个现象，看起来更像引擎坏了，
     * 不像"定义被删了"。{@code getDefinition} 本来就为这件事抛异常。
     *
     * <p>网关同样收进索引：token 停在排他网关上等出线是真实存在的状态
     *（条件都不成立时它就停在那儿），这时名字与类型空着会让排障的人以为这不是节点。
     */
    private Map<String, WfNode> nodeIndexOf(WfProcessInstance instance) {
        WfDefinition definition = repositoryService.getDefinition(instance.getDefinitionKey(),
                instance.getDefinitionVersion());
        Map<String, WfNode> index = new HashMap<>();
        for (WfNode node : definition.getNodes()) {
            index.put(node.getId(), node);
        }
        return index;
    }

    /**
     * 标记「本流程当前是否有多条未结束的 token」。
     *
     * <p><b>不按兄弟数判</b>，理由写在 {@link WfActivityInstanceView#isConcurrent} 上：
     * 本仓的并行网关 fork 出来的是<b>父子链</b>（第一条出线留在父 token 上），
     * 两条并行分支的 activityId 天然不同，按兄弟数判这个位永远是 false。
     *
     * <p>这一位标在<b>每一个</b>节点上而不是只标根：调用方常常只展开了某个分支的
     * 子树（前端树控件按需展开），只看根的话得先把整棵树拉回来才知道有没有并行。
     */
    private void markConcurrent(WfActivityInstanceView root, int activeTokens) {
        ArrayDeque<WfActivityInstanceView> pending = new ArrayDeque<>();
        pending.push(root);
        boolean concurrent = activeTokens > 1;
        while (!pending.isEmpty()) {
            WfActivityInstanceView node = pending.pop();
            node.setConcurrent(concurrent);
            for (WfActivityInstanceView child : node.getChildActivityInstances()) {
                pending.push(child);
            }
        }
    }

    /**
     * 把该 token 已经离开过的节点挂到它下面。
     *
     * <p>按 {@code executionId} 归组，而不是按时间序去猜父子 ——
     * 猜的那条路在并行分支上必然出错，而出错的表现是"树看着挺像那么回事"。
     * 历史行里 {@code executionId} 为空的（理论上不该有）挂不到任何 token，
     * 计入 {@code unattached} 并写日志：宁可让人知道有行没归上，
     * 也不要让它们消失后无从追查。
     */
    private void attachTransitions(String processInstanceId,
                                   Collection<WfActivityInstanceView> nodes) {
        Map<String, WfActivityInstanceView> byExecution = new HashMap<>();
        for (WfActivityInstanceView node : nodes) {
            byExecution.put(node.getExecutionId(), node);
        }
        List<WfActivityInstance> history = persistence.findActivityInstances(processInstanceId);
        if (history.size() > MAX_HISTORY) {
            throw new WfEngineException("流程 " + processInstanceId + " 有 " + history.size()
                    + " 条活动历史，超过上限 " + MAX_HISTORY);
        }
        int unattached = 0;
        for (WfActivityInstance record : history) {
            WfActivityInstanceView node = byExecution.get(record.getExecutionId());
            if (node == null) {
                unattached++;
                continue;
            }
            WfTransitionInstanceView transition = new WfTransitionInstanceView();
            transition.setId(record.getId());
            transition.setParentActivityInstanceId(node.getId());
            transition.setProcessInstanceId(record.getProcessInstanceId());
            transition.setActivityId(record.getActivityId());
            transition.setActivityName(record.getActivityName());
            transition.setActivityType(record.getActivityType());
            transition.setExecutionId(record.getExecutionId());
            transition.setAssignee(record.getAssignee());
            transition.setOutcome(record.getOutcome());
            transition.setDetail(record.getDetail());
            transition.setDurationMillis(record.getDurationMillis());
            transition.setStartTime(timeOf(record.getStartTime()));
            transition.setEndTime(timeOf(record.getEndTime()));
            node.getChildTransitionInstances().add(transition);
        }
        if (unattached > 0) {
            log.warn("流程 {} 有 {} 条活动历史挂不到任何执行令牌上"
                    + "（executionId 为空，或对应的 token 已被删除）",
                    processInstanceId, unattached);
        }
    }

    private static Long timeOf(Date date) {
        return date == null ? null : date.getTime();
    }

    private static List<String> idsOf(List<WfActivityInstanceView> nodes) {
        List<String> ids = new ArrayList<>();
        for (WfActivityInstanceView node : nodes) {
            ids.add(node.getId());
        }
        return ids;
    }
}