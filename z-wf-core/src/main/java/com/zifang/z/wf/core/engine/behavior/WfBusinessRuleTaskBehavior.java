package com.zifang.z.wf.core.engine.behavior;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.engine.dmn.WfDmnDecisionResult;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;
import com.zifang.z.wf.core.service.WfDecisionService;
import com.zifang.z.wf.core.service.WfEngineException;

/**
 * 业务规则任务 —— 同步求值一张 DMN 决策表，把结论写进流程变量。
 *
 * <p>与 {@code serviceTask} 的区别是<b>不需要业务方写代码</b>；
 * 与 {@code scriptTask} 的区别是<b>规则与流程分开部署</b> ——
 * 决策表有独立版本（见 {@code WfDecisionService}），改规则不必重新部署流程。
 *
 * <p><b>四种结果映射</b>（名字与 Camunda 一致，因为它们描述的是结果的**形状**
 * 而不是本仓的私有约定）：{@code singleEntry} 取唯一那个值、
 * {@code singleResult} 取唯一那一行的输出 Map、{@code collectEntries} 收集每一行的
 * 第一个输出、{@code resultList}（默认）给全部行。
 * 选哪一个取决于<b>下游要什么</b>：网关条件比字符串就该用 singleEntry，
 * 要把多行结果整张交给后续节点就该用 resultList。
 *
 * <p><b>映射不适用时报错而不是给一个凑合的结果</b>：
 * {@code singleEntry} 撞上多行时返回第一条，会让流程带着一个"看起来正常"的
 * 结论继续走，而那个结论取决于命中顺序。
 *
 * @author zifang
 */
public class WfBusinessRuleTaskBehavior implements WfActivityBehavior {

    private static final Logger log = LoggerFactory.getLogger(WfBusinessRuleTaskBehavior.class);

    @Override
    public WfTask execute(WfContext context, WfNode node, WfExecution execution) {
        WfDecisionService decisions = context.getDecisionService();
        if (decisions == null) {
            // 不是 NPE：引擎没被接上决策服务是一处**接线漏了**，
            // 而 NPE 的报错既不说是哪个节点、也不说缺什么
            throw new WfEngineException("节点 [" + node.getId() + "] 是业务规则任务，"
                    + "但引擎没有接上决策服务（WfDecisionService）—— "
                    + "这是接线问题，不是这张流程写得有问题");
        }

        String key = resolveDecisionKey(context, node);
        WfDmnDecisionResult result = evaluate(context, node, decisions, key);
        String target = node.getResultVariable();
        // 部署期已经报过 ERROR 说 resultVariable 必填；这里再挡一次是因为
        // 定义可能来自 JSON 或历史数据，绕过校验器落到运行期
        if (target == null || target.trim().isEmpty()) {
            throw new WfEngineException("业务规则任务 [" + node.getId() + "] 没有配 resultVariable —— "
                    + "决策结果没有别的出口，不给它写进哪个变量的话这个节点等于什么都没做");
        }
        context.setVariable(target.trim(), map(result, mapperOf(node), key));
        return null;
    }

    private WfDmnDecisionResult evaluate(WfContext context, WfNode node,
                                        WfDecisionService decisions, String key) {
        Map<String, Object> variables = context.mergedVariables();
        String binding = bindingOf(node);
        if ("version".equals(binding)) {
            String version = node.getDecisionRefVersion();
            if (version == null || version.trim().isEmpty()) {
                throw new WfEngineException("业务规则任务 [" + node.getId() + "] 用了 decisionRefBinding=\"version\" "
                        + "却没有 decisionRefVersion —— 要哪个版本没有答案，引擎只能去猜，"
                        + "而猜错的结果是同一份流程两次跑出不同结论");
            }
            return decisions.evaluateDecision(key, parseVersion(version), variables);
        }
        return decisions.evaluateDecision(key, variables);
    }

    /**
     * 解析要执行的决策 key。
     *
     * <p><b>只有带 {@code ${}} / {@code #{}} 前缀的才当表达式</b>。
     * 裸串一律当字面 key —— 拿裸串去求值的话，{@code approvalLevel} 会被当成变量名，
     * 而求值器对未定义变量是 fail-closed（返回 null），于是每张决策都变成"找不到"。
     * 这个坑很隐蔽：它不报"决策不存在"，它报的是"决策 null 不存在"。
     */
    private String resolveDecisionKey(WfContext context, WfNode node) {
        String ref = node.getDecisionRef();
        if (ref == null || ref.trim().isEmpty()) {
            // 部署期会先报 ERROR；这里是给绕过校验的定义留一道明确的口子
            throw new WfEngineException("业务规则任务 [" + node.getId() + "] 没有配 decisionRef —— "
                    + "它要求值哪张决策表没有答案");
        }
        String trimmed = ref.trim();
        if (!trimmed.startsWith("${") && !trimmed.startsWith("#{")) {
            return trimmed;
        }
        Object resolved = context.getExpressionEvaluator()
                .evalRaw(trimmed, context.mergedVariables());
        if (resolved == null || String.valueOf(resolved).trim().isEmpty()) {
            throw new WfEngineException("业务规则任务 [" + node.getId() + "] 的 decisionRef 表达式 ["
                    + ref + "] 求出来是空 —— 拿空 key 去查决策只会得到一句"
                    + "「决策 null 不存在」，把真正的原因（表达式里的变量没有值）藏起来");
        }
        return String.valueOf(resolved).trim();
    }

    private String bindingOf(WfNode node) {
        String binding = node.getDecisionRefBinding();
        return binding == null || binding.trim().isEmpty() ? "latest" : binding.trim();
    }

    private String mapperOf(WfNode node) {
        String mapper = node.getMapDecisionResult();
        return mapper == null || mapper.trim().isEmpty() ? "resultList" : mapper.trim();
    }

    private int parseVersion(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new WfEngineException("decisionRefVersion 必须是整数，实际是 [" + raw + "]");
        }
    }

    /**
     * 结果 → 要写进变量的那个值。
     *
     * <p>四种映射各自对"结果的形状"有要求，对不上就报错。
     * 报错的理由与 {@code singleOutput()} 一样：给一个凑合的结果，
     * 流程会带着一个看起来正常的值继续走下去，而实际上它与决策算出来的东西无关。
     */
    private Object map(WfDmnDecisionResult result, String mapper, String key) {
        if ("singleEntry".equals(mapper)) {
            return singleEntry(result, key);
        }
        if ("singleResult".equals(mapper)) {
            if (result.getRows().size() > 1) {
                throw notSingle(result, key, mapper, "命中了 " + result.getRows().size() + " 行");
            }
            return result.getRows().isEmpty()
                    ? new LinkedHashMap<String, Object>()
                    : result.getRows().get(0).getOutputs();
        }
        if ("collectEntries".equals(mapper)) {
            List<Object> collected = new ArrayList<>();
            for (WfDmnDecisionResult.Row row : result.getRows()) {
                Map<String, Object> outputs = row.getOutputs();
                if (outputs.size() != 1) {
                    throw new WfEngineException("决策 [" + key + "] 的结果有 " + outputs.size()
                            + " 个输出列，collectEntries 只适用于「每一行恰好一个输出列」的表。"
                            + "有多个输出列时请改用 resultList 或 singleResult");
                }
                collected.add(outputs.values().iterator().next());
            }
            return collected;
        }
        if ("resultList".equals(mapper)) {
            List<Map<String, Object>> all = new ArrayList<>();
            for (WfDmnDecisionResult.Row row : result.getRows()) {
                all.add(row.getOutputs());
            }
            return all;
        }
        // 部署期报过 ERROR；这里是给绕过校验的定义留一道明确的口子
        throw new WfEngineException("未知的 mapDecisionResult [" + mapper + "]。"
                + "合法值: singleEntry / singleResult / collectEntries / resultList");
    }

    private Object singleEntry(WfDmnDecisionResult result, String key) {
        if (result.getRows().size() > 1) {
            throw notSingle(result, key, "singleEntry", "命中了 " + result.getRows().size() + " 行");
        }
        Map<String, Object> outputs = result.singleOutput();
        if (outputs.size() != 1) {
            throw new WfEngineException("决策 [" + key + "] 的结果有 " + outputs.size()
                    + " 个输出列，singleEntry 只适用于「恰好一个输出列」的表。"
                    + "有多个输出列时请改用 singleResult 或 resultList");
        }
        return outputs.values().iterator().next();
    }

    private WfEngineException notSingle(WfDmnDecisionResult result, String key,
                                       String mapper, String actual) {
        log.warn("决策 [{}] 用 {} 读取失败: {}", key, mapper, actual);
        return new WfEngineException("决策 [" + key + "] " + actual
                + "，不能用 " + mapper + " 映射 —— 取第一条的话，流程会带着一个"
                + "「看起来正常」的结论继续走，而这个结论取决于命中顺序，"
                + "同一类单据两次会算出不同结果。请把 mapDecisionResult 改成 "
                + "resultList（拿全部行）或把表改成只命中一条");
    }
}