package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.model.WfComment;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * 变量服务 —— 流程级与任务级的变量读写。
 *
 * <p>变量一直存着（{@link WfProcessInstance#getVariables()} 与
 * {@link WfTask#getVariables()} 两处），但此前<b>没有任何服务 API 去读写它们</b>：
 * 调用方只能自己 {@code findProcessInstance} 拿到实体、直接改 map、再
 * {@code saveProcessInstance}。这条路能跑通，但有两个绕不开的问题：
 * <ul>
 *   <li>绕过了本层的所有前置校验（终态实例、可空的实例 id…）</li>
 *   <li>改完 map 之后如果忘了 {@code nextRevision()}，乐观锁形同虚设 ——
 *       而乐观锁正是并发改同一个审批单时唯一的保护</li>
 * </ul>
 * 这个服务把这条路径收回来。
 *
 * <h3>为什么改变量要留审计痕迹</h3>
 * 审批系统里"提交后有人把金额从 1000 改成 500"是必须能回答的问题。
 * 变量本身不带修改人也不带时间，而 {@link WfComment} 已经是一条现成的、
 * 有操作人有时间戳、并且已经能从 {@code GET /comments} 查到的记录。
 * 所以每次变更都落一条 comment，而不是新造一套审计表 ——
 * 新造一套的结果是"审计表和评论表内容重复，但只有一套有人查"。
 *
 * <h3>null 的语义</h3>
 * 本仓<b>不存在"变量值为 null"这个状态</b>：{@code WfContext#setVariable(name, null)}
 * 的行为是<b>删除</b>该变量。因此 {@link #setVariable} 显式拒绝 null，
 * 逼调用方改用 {@link #removeVariable} —— 否则"给变量赋个 null"会静默
 * 变成"把变量删了"，而条件表达式里引用一个被删掉的变量会走 fail-closed
 * 分支改变流程走向。
 *
 * @author zifang
 */
public class WfVariableService {

    private static final Logger log = LoggerFactory.getLogger(WfVariableService.class);

    /** 变更记录使用的 comment type。前端可据此把变量变更与人工评论区分渲染。 */
    public static final String COMMENT_TYPE_VARIABLE = "variable";

    private final WfPersistence persistence;
    private final WfIdGenerator idGenerator;

    public WfVariableService(WfPersistence persistence, WfIdGenerator idGenerator) {
        this.persistence = persistence;
        this.idGenerator = idGenerator;
    }

    // ==================== 读取 ====================

    /**
     * 读单个流程级变量。
     *
     * @return 不存在时返回 {@code null}（区分不了"值为 null"和"不存在"，
     *         因为本仓没有 null 值——见类注释）
     */
    public Object getVariable(String processInstanceId, String name) {
        return requireInstance(processInstanceId).getVariables().get(name);
    }

    /** 流程级变量的可修改副本。 */
    public Map<String, Object> getVariables(String processInstanceId) {
        return new LinkedHashMap<>(requireInstance(processInstanceId).getVariables());
    }

    /**
     * 变量是否存在。
     *
     * <p>这个方法存在的意义不只是方便：条件表达式求值要区分
     * "变量没定义"和"变量定义成 0"，而 fail-closed 判定依赖的正是这个区别。
     */
    public boolean hasVariable(String processInstanceId, String name) {
        return requireInstance(processInstanceId).getVariables().containsKey(name);
    }

    /** 读单个任务级变量。 */
    public Object getTaskVariable(String taskId, String name) {
        return requireTask(taskId).getVariables().get(name);
    }

    /** 任务级变量的可修改副本。 */
    public Map<String, Object> getTaskVariables(String taskId) {
        return new LinkedHashMap<>(requireTask(taskId).getVariables());
    }

    // ==================== 写入 ====================

    /**
     * 写单个流程级变量。
     *
     * @return 变更后的实例（含新的 revision）
     * @throws WfEngineException 实例不存在、已结束，或值为 {@code null}
     */
    public WfProcessInstance setVariable(String processInstanceId, String name,
                                         Object value, String operatorId) {
        Map<String, Object> one = new HashMap<>();
        one.put(name, value);
        return setVariables(processInstanceId, one, operatorId);
    }

    /**
     * 批量写流程级变量。
     *
     * <p><b>整批只落一次库、只 bump 一次 revision。</b> 逐个保存看着自然，
     * 但那样会出现"前三个已写、第四个冲突"——实例停在部分更新的状态，
     * 而调用方拿到的是一个异常，无从知道已经改了哪几个。
     * 一次 CAS 要么全成、要么全不成，这才是批量该有的语义。
     *
     * @throws WfEngineException 实例已结束，或 map 里含 {@code null} 值
     */
    public WfProcessInstance setVariables(String processInstanceId,
                                          Map<String, Object> values, String operatorId) {
        WfProcessInstance instance = requireInstance(processInstanceId);
        requireMutable(instance);
        if (values == null || values.isEmpty()) {
            return instance;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            requireName(entry.getKey());
            if (entry.getValue() == null) {
                throw new WfEngineException("变量 [" + entry.getKey() + "] 的值为 null。"
                        + "本仓没有'值为 null'这个状态，写 null 等于删除该变量；"
                        + "请改用 removeVariable，避免把赋值静默变成删除");
            }
        }

        // 每个变量记一条审计，而不是把整批拼成一条。
        // 拼成一条的话，"amount 什么时候被谁改的"就只能拿 LIKE 去匹配一整段文本，
        // 而 amount 会命中 discount_amount —— 审计查询必须精确到变量名。
        List<String> traces = new ArrayList<String>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object before = instance.getVariables().get(entry.getKey());
            instance.getVariables().put(entry.getKey(), entry.getValue());
            traces.add(entry.getKey() + ": " + render(before)
                    + " -> " + render(entry.getValue()));
        }
        persistWithAudit(instance, operatorId, traces);
        return instance;
    }

    /**
     * 删除一个流程级变量。
     *
     * <p>删不存在的变量<b>不报错</b>：删除操作的语义是"保证它不存在"，
     * 而非"它必须本来存在"。让重复删除失败会让重试逻辑变得很难写。
     */
    public WfProcessInstance removeVariable(String processInstanceId, String name,
                                            String operatorId) {
        WfProcessInstance instance = requireInstance(processInstanceId);
        requireMutable(instance);
        requireName(name);
        if (!instance.getVariables().containsKey(name)) {
            return instance;
        }
        Object before = instance.getVariables().remove(name);
        persistWithAudit(instance, operatorId,
                java.util.Collections.singletonList("remove " + name + ": " + render(before)
                        + " -> (已删除)"));
        return instance;
    }

    // ==================== 任务级 ====================

    /**
     * 写任务级变量。
     *
     * <p>任务级变量只随这条任务走，不进流程级命名空间，因此<b>不会被后续
     * 条件表达式当作流程变量读到</b>。这一点有测试钉着（见
     * {@code WfTaskVariableVisibilityTest}）：泄漏到流程级会让条件读到不该读的值。
     * 要影响流程走向请用 {@link #setVariable}（全局）或
     * {@link #setVariableLocal}（只影响某条并行分支）。
     */
    public WfTask setTaskVariable(String taskId, String name, Object value, String operatorId) {
        requireName(name);
        if (value == null) {
            throw new WfEngineException("变量 [" + name + "] 的值为 null，请改用 removeTaskVariable");
        }
        WfTask task = requireTask(taskId);
        if (!task.isOpen()) {
            throw new WfEngineException("任务已办结或已作废，不能再改它的变量: " + taskId);
        }
        Object before = task.getVariables().put(name, value);
        task.nextRevision();
        persistence.saveTask(task);
        recordTaskAudit(task, operatorId,
                "set " + name + ": " + render(before) + " -> " + render(value));
        return task;
    }

    /** 删除任务级变量。 */
    public WfTask removeTaskVariable(String taskId, String name, String operatorId) {
        requireName(name);
        WfTask task = requireTask(taskId);
        if (!task.isOpen()) {
            throw new WfEngineException("任务已办结或已作废，不能再改它的变量: " + taskId);
        }
        if (!task.getVariables().containsKey(name)) {
            return task;
        }
        Object before = task.getVariables().remove(name);
        task.nextRevision();
        persistence.saveTask(task);
        recordTaskAudit(task, operatorId,
                "remove " + name + ": " + render(before) + " -> (已删除)");
        return task;
    }

    // ==================== 执行（token）级 ====================

    /**
     * 写<b>某一条 token</b> 的局部变量。
     *
     * <p><b>它是本仓唯一的「中间那条作用域」</b>，填的是并行分支真正的需求：
     * 两条并行分支各自要不同的局部值时，写流程级变量会互相覆盖（后写的赢），
     * 写任务级变量则对条件表达式完全不可见（见 {@link #setTaskVariable}）——
     * 只剩"污染全局"或"完全无效"两个都不对的选项。token 级正好在中间：
     * 它只在这条分支的推进里可见，别的分支读不到。
     *
     * <p><b>它对条件表达式可见</b>，靠的是 {@code WfContext#mergedVariables()}
     * 早就把当前 token 的变量并了进去 —— 引擎内部量（{@code loopCounter} /
     * {@code loopAssignee}）就是走这条路生效的。缺的从来不是求值，
     * 而是<b>一个能让业务方写进去的入口</b>。
     *
     * <p><b>token 结束即失效</b>：局部变量随它那条分支走，分支走完就没了。
     * 需要跨分支的请用 {@link #setVariable}。
     */
    public WfExecution setVariableLocal(String executionId, String name,
                                        Object value, String operatorId) {
        WfExecution execution = requireExecution(executionId);
        requireName(name);
        if (value == null) {
            throw new WfEngineException("变量 [" + name + "] 的值为 null，请改用 removeVariableLocal");
        }
        Object before = execution.getVariables().put(name, value);
        // 直接 saveExecution 而不是等 persistAll：这里是服务层直改存储，没有 context 可依托。
        // 注意 WfExecution **没有** revision 字段、saveExecution 的 UPDATE 也不带乐观锁
        // （WHERE 只有 EXEC_ID）—— 这是本仓既有的事实，不是这里偷的懒。
        // 对本方法而言影响有限：并行分支各改各的 token，EXEC_ID 不同互不干扰；
        // 真正没有保护的是"两个请求同时改同一条 token"，那在本 API 出现前就存在了
        persistence.saveExecution(execution);
        recordExecutionAudit(execution, operatorId,
                "set " + name + ": " + render(before) + " -> " + render(value));
        return execution;
    }

    /**
     * 读某条 token 的局部变量。
     *
     * <p><b>读的是局部这一层，不做作用域回退</b>：token 上没设就是 {@code null}，
     * 哪怕流程级或更外层有同名值。回退会让"这个分支覆盖了什么"变得无法回答，
     * 而并行分支排障时问的恰恰是这个。
     */
    public Object getVariableLocal(String executionId, String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        WfExecution execution = persistence.findExecution(executionId);
        return execution == null ? null : execution.getVariables().get(name);
    }

    /** 某条 token 的全部局部变量。token 不存在时返回空 Map 而不是 null。 */
    public Map<String, Object> getVariablesLocal(String executionId) {
        WfExecution execution = persistence.findExecution(executionId);
        return execution == null
                ? new HashMap<String, Object>()
                : new LinkedHashMap<String, Object>(execution.getVariables());
    }

    public boolean hasVariableLocal(String executionId, String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        WfExecution execution = persistence.findExecution(executionId);
        return execution != null && execution.getVariables().containsKey(name);
    }

    /**
     * 删一条 token 的局部变量。删不存在的<b>不报错</b> ——
     * 删除的语义是"保证它不存在"，而非要它本来存在。
     */
    public WfExecution removeVariableLocal(String executionId, String name, String operatorId) {
        WfExecution execution = requireExecution(executionId);
        requireName(name);
        if (!execution.getVariables().containsKey(name)) {
            return execution;
        }
        Object before = execution.getVariables().remove(name);
        persistence.saveExecution(execution);
        recordExecutionAudit(execution, operatorId,
                "remove " + name + ": " + render(before) + " -> (已删除)");
        return execution;
    }

    /**
     * 任务所在的 token。
     *
     * <p><b>REST 层用它代替 executionId 作为入参</b>：执行树是引擎内部结构，
     * 仓里已有测试钉着「任务响应里不得出现 executionId」。而任务天然绑定一条 token，
     * 调用方手上有的恰恰是 taskId —— 让他自己去 executions 端点反查
     * 「哪个 token 停在这个任务上」，一旦猜错改的就是别的分支，
     * 症状是「并行流程里有一条莫名其妙走了另一条路」。
     *
     * @throws WfEngineException 任务不存在，或没有绑定 token
     */
    public String executionIdOfTask(String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new WfEngineException("任务 id 不能为空");
        }
        WfTask task = persistence.findTask(taskId);
        if (task == null) {
            throw new WfEngineException("任务不存在: " + taskId);
        }
        if (task.getExecutionId() == null || task.getExecutionId().trim().isEmpty()) {
            throw new WfEngineException("任务 " + taskId + " 没有绑定 token，"
                    + "无法确定它属于哪条分支。该任务可能是在分支之外创建的"
                    + "（例如流程级待办），那种情况下没有分支级变量可言");
        }
        return task.getExecutionId();
    }

    private void recordExecutionAudit(WfExecution execution, String operatorId, String trace) {
        persistence.saveComment(new WfComment(idGenerator.nextCommentId(),
                execution.getProcessInstanceId(), operatorId, COMMENT_TYPE_VARIABLE,
                "token " + execution.getActivityId() + "(" + execution.getId() + ") " + trace));
        log.info("token {} 变量变更 by {}: {}", execution.getId(), operatorId, trace);
    }

    private WfExecution requireExecution(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new WfEngineException("token id 不能为空");
        }
        WfExecution execution = persistence.findExecution(id);
        if (execution == null) {
            throw new WfEngineException("token 不存在: " + id);
        }
        if (execution.isEnded()) {
            throw new WfEngineException("token 已结束，它的局部变量随这条分支一起失效，"
                    + "改它等于伪造记录: " + id);
        }
        return execution;
    }

    // ==================== 内部 ====================

    /**
     * 落库 + 记审计。
     *
     * <p>顺序是"先记审计、后落实例"还是反过来，取决于失败时哪个状态更可接受。
     * 这里选<b>先落实例</b>：乐观锁冲突时实例没变，如果审计先落，
     * 就会留下一条"某某改了变量"的记录而实际没改成 ——
     * 审计记录说谎比少一条记录危险得多。
     * 代价是实例落库后、写审计前崩溃会丢一条审计记录，
     * 这种情况由 comment 里的时间戳与实例 revision 变化对不上体现出来。
     */
    private void persistWithAudit(WfProcessInstance instance, String operatorId,
                                  List<String> traces) {
        instance.nextRevision();
        persistence.saveProcessInstance(instance);
        if (traces != null) {
            for (String trace : traces) {
                persistence.saveComment(new WfComment(idGenerator.nextCommentId(),
                        instance.getId(), operatorId, COMMENT_TYPE_VARIABLE, trace));
            }
        }
        log.info("流程 {} 变量变更 by {}: {}", instance.getId(), operatorId, traces);
    }

    private void recordTaskAudit(WfTask task, String operatorId, String trace) {
        persistence.saveComment(new WfComment(idGenerator.nextCommentId(),
                task.getProcessInstanceId(), operatorId, COMMENT_TYPE_VARIABLE,
                "任务 " + task.getName() + "(" + task.getId() + ") " + trace));
        log.info("任务 {} 变量变更 by {}: {}", task.getId(), operatorId, trace);
    }

    /** 变量不存在时渲染成 {@code (未设置)}，而不是和 null 值混淆。 */
    private String render(Object value) {
        if (value == null) {
            return "(未设置)";
        }
        String text = String.valueOf(value);
        return text.length() > 200 ? text.substring(0, 200) + "…(已截断)" : text;
    }

    private void requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new WfEngineException("变量名不能为空");
        }
    }

    /**
     * 终态实例不允许改变量。
     *
     * <p>流程结束之后再改变量，审批轨迹上会出现"已归档的单据金额被调整"，
     * 而且没有任何节点会再用到这个值——改它是纯粹的伪造记录。
     */
    private void requireMutable(WfProcessInstance instance) {
        if (instance.getStatus().isTerminal()) {
            throw new WfEngineException("流程实例已结束（" + instance.getStatus()
                    + "），不允许再修改变量: " + instance.getId());
        }
    }

    private WfProcessInstance requireInstance(String id) {
        WfProcessInstance instance = persistence.findProcessInstance(id);
        if (instance == null) {
            throw new WfEngineException("流程实例不存在: " + id);
        }
        return instance;
    }

    private WfTask requireTask(String id) {
        WfTask task = persistence.findTask(id);
        if (task == null) {
            throw new WfEngineException("任务不存在: " + id);
        }
        return task;
    }
}
