package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.dmn.WfDmnDecision;
import com.zifang.z.wf.core.definition.dmn.WfDmnParser;
import com.zifang.z.wf.core.engine.dmn.WfDmnDecisionResult;
import com.zifang.z.wf.core.engine.dmn.WfDmnEvaluator;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.persistence.WfPersistence;

/**
 * DMN 决策服务 —— 决策表的部署与求值入口。
 *
 * <p>对应 Camunda 的 {@code DecisionService}。API 形状刻意对齐它
 * （{@code deployDecision} / {@code evaluateDecisionByKey} / {@code deleteDecision}），
 * 这样从 Camunda 迁过来的调用方不用改调用点；而<b>语义与不支持项</b>
 * 写在这一轮的文档差异表里，不靠"看起来一样"蒙混。
 *
 * <p><b>只支持决策表，不支持决策图。</b>决策图（{@code informationRequirement}
 * 组成的有向图）要求按拓扑顺序求值多个决策、每跳的输入是上一跳的输出 ——
 * 那是一条与决策表完全不同的执行路径。折成"按声明顺序跑一遍"会在
 * 依赖顺序与声明顺序不一致的图上算出错误结果，而这种错误<b>不会报错</b>。
 *
 * <p><b>只支持本仓 EL 写得出来的表达式，不支持 FEEL。</b>DMN 规范里的条件
 * 写的是 FEEL（{@code [1..100] >}、{@code date("2024-01-01")}、{@code @"P1D"} 之类），
 * 而本仓用的是 z-util 的 EL（比较 / 逻辑 / 三目 / 括号 / 字符串字面量 / 方法调用）。
 * 两者<b>不是同一门语言</b>：把 FEEL 表达式喂给 EL，多半会求不出值。
 * 所以部署期就把能识别的 FEEL 构造挡下来并说清原因 ——
 * 留在运行期的话，症状是「某条规则永远不命中」，而作者会以为是条件写错了。
 *
 * @author zifang
 */
public class WfDecisionService {

    private static final Logger log = LoggerFactory.getLogger(WfDecisionService.class);

    private final WfPersistence persistence;
    private final WfDmnParser parser = new WfDmnParser();
    private final WfDmnEvaluator evaluator;

    public WfDecisionService(WfPersistence persistence) {
        this(persistence, new WfDmnEvaluator(new WfExpressionEvaluator()));
    }

    public WfDecisionService(WfPersistence persistence, WfDmnEvaluator evaluator) {
        this.persistence = persistence;
        this.evaluator = evaluator;
    }

    // ==================== 部署 ====================

    /**
     * 解析但不落库 —— 用来校验一份 DMN 能不能跑（CI 里挡文件用），不产生版本。
     *
     * @return 文件里的全部决策（顺序与文件里一致）
     * @throws WfDefinitionException 解析失败、含 FEEL 构造，或一个 decision 都没有
     */
    public List<WfDmnDecision> parseDecision(String dmnXml) {
        List<WfDmnDecision> decisions = parser.parse(dmnXml);
        for (WfDmnDecision decision : decisions) {
            guardAgainstFeel(decision);
        }
        return decisions;
    }

    /**
     * 部署决策定义。
     *
     * <p>一份文件里的<b>每个</b> {@code <decision>} 各自成为一个部署单元，
     * 版本从 1 开始；同一 key 再次部署时版本 +1 且<b>旧版本保留</b>
     * （决策表是会迭代的，而在途的流程引用的是当时的表）。
     *
     * @return 本次部署产生的全部决策 key（按文件里顺序）
     */
    public List<String> deployDecision(String dmnXml) {
        List<String> keys = new ArrayList<>();
        for (WfDmnDecision decision : parseDecision(dmnXml)) {
            int version = nextVersion(decision.getKey());
            decision.setVersion(version);
            decision.setDeployTime(new Date());
            persistence.saveDecision(decision);
            log.info("部署决策定义: key={} version={} hitPolicy={} rules={}",
                    decision.getKey(), version, decision.getTable().getHitPolicy(),
                    decision.getTable().getRules().size());
            keys.add(decision.getKey());
        }
        return keys;
    }

    private int nextVersion(String key) {
        WfDmnDecision latest = persistence.findLatestDecision(key);
        return latest == null ? 1 : latest.getVersion() + 1;
    }

    // ==================== 查询 ====================

    /** 按 key 取最新版本；不存在返回 {@code null}（调用方自己决定要不要报错）。 */
    public WfDmnDecision findDecisionByKey(String key) {
        return persistence.findLatestDecision(key);
    }

    /** 按 key + version 取；不存在返回 {@code null}。 */
    public WfDmnDecision findDecisionByKey(String key, int version) {
        return persistence.findDecision(key, version);
    }

    /** 全部版本，按 version 倒序。 */
    public List<WfDmnDecision> findDecisionsByKey(String key) {
        return persistence.findDecisionVersions(key);
    }

    /**
     * 物理删除某个版本。
     *
     * <p><b>不做"是否在途"检查</b>：决策可能被任何流程引用，服务层没有那个口径，
     * 猜错比不拦更糟（见 {@code WfPersistence#deleteDecision}）。
     * 调用方应当先 {@code terminate} 掉在途实例 —— 撤销一张决策和终止在办的单
     * 是两个决策，跟流程定义删除是同一件事。
     *
     * @return 是否真的删掉了那一行
     */
    public boolean deleteDecision(String key, int version) {
        return persistence.deleteDecision(key, version);
    }

    // ==================== 求值 ====================

    /**
     * 求<b>最新版本</b>的决策。
     *
     * @param key       决策 key（{@code <decision id>}）
     * @param variables 输入变量，通常是流程实例变量
     * @throws WfEngineException 决策不存在或没有决策表（部署期被绕过）
     * @throws com.zifang.z.wf.core.engine.dmn.WfDmnViolationException 命中策略被违反
     */
    public WfDmnDecisionResult evaluateDecision(String key, Map<String, Object> variables) {
        WfDmnDecision decision = persistence.findLatestDecision(key);
        if (decision == null) {
            throw new WfEngineException("决策 [" + key + "] 不存在（没有任何版本）。"
                    + "若 key 是从别处抄来的，先确认部署过："
                    + "DMN 的 key 取自 <decision id>，不是文件名");
        }
        return evaluate(decision, variables);
    }

    /** 求指定版本的决策。 */
    public WfDmnDecisionResult evaluateDecision(String key, int version,
                                                 Map<String, Object> variables) {
        WfDmnDecision decision = persistence.findDecision(key, version);
        if (decision == null) {
            throw new WfEngineException("决策 [" + key + "] 的版本 " + version + " 不存在。"
                    + "已有的版本: " + versionsOf(key));
        }
        return evaluate(decision, variables);
    }

    /** 对一个已加载的决策求值 —— 循环里复用同一个决策时省掉每次查库。 */
    public WfDmnDecisionResult evaluate(WfDmnDecision decision, Map<String, Object> variables) {
        if (decision == null || decision.getTable() == null) {
            throw new WfEngineException("决策 ["
                    + (decision == null ? "?" : decision.getKey())
                    + "] 没有决策表。部署期已经挡掉了没有表的情况，"
                    + "走到这里说明部署被绕过了（自定义装配，或直接调引擎）");
        }
        return evaluator.evaluate(decision.getTable(), variables, decision.getKey());
    }

    private String versionsOf(String key) {
        List<WfDmnDecision> versions = persistence.findDecisionVersions(key);
        if (versions.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (WfDmnDecision decision : versions) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(decision.getVersion());
        }
        return sb.toString();
    }

    // ==================== FEEL 闸门 ====================

    /**
     * 部署期挡下 FEEL 特有构造。
     *
     * <p><b>刻意不追求"检出全部 FEEL"</b>，只挡高置信度的那几类：
     * 检测不出来的那部分留给运行期 fail-closed（表达式求不出值 ⇒ 该规则不命中）。
     * 反过来（"挡住一切可能的 FEEL"）只能靠引入一个 FEEL 词法器，
     * 而那正是把 FEEL 引进来要付出的代价。
     *
     * <p>所以这里的措辞是"**这一条本实现写不出来**"而不是"这不是合法的 DMN" ——
     * 它完全可能是合法的 FEEL，只是本仓的 EL 不是 FEEL。
     */
    private void guardAgainstFeel(WfDmnDecision decision) {
        WfDmnDecision.WfDmnTable table = decision.getTable();
        if (table == null) {
            return;
        }
        for (String expression : table.getInputExpressions()) {
            rejectFeel(decision, "输入列", expression);
        }
        int ruleIndex = 0;
        for (WfDmnDecision.WfDmnRule rule : table.getRules()) {
            ruleIndex++;
            for (int i = 0; i < rule.getInputEntries().size(); i++) {
                rejectFeel(decision, "第 " + ruleIndex + " 条规则的第 " + (i + 1) + " 个输入项",
                        rule.getInputEntries().get(i));
            }
            for (int i = 0; i < rule.getOutputEntries().size(); i++) {
                rejectFeel(decision, "第 " + ruleIndex + " 条规则的第 " + (i + 1) + " 个输出项",
                        rule.getOutputEntries().get(i));
            }
        }
    }

    private void rejectFeel(WfDmnDecision decision, String where, String expression) {
        String feature = feelFeature(expression);
        if (feature != null) {
            throw new WfDefinitionException("决策 [" + decision.getKey() + "] 的 " + where
                    + " [" + expression + "] 用了 FEEL 的 " + feature + "。"
                    + "DMN 规范里的条件写的是 FEEL，而本实现用的是 z-util 的 EL"
                    + "（比较 / 逻辑 / 三目 / 括号 / 字符串字面量 / 方法调用），"
                    + "两者不是同一门语言。"
                    + "留着它的话，求值会失败或静默不命中 —— "
                    + "后者更糟：那条规则永远不成立，而表上完全看不出异常。"
                    + "改写成 EL 写法即可（区间改写成两个比较、"
                    + "date() 改成流程变量或时间定时器算好的值）");
        }
    }

    /**
     * 这段表达式里有没有高置信度的 FEEL 构造。
     *
     * @return 命中的构造名（便于报错点名）；没有则返回 {@code null}
     */
    private String feelFeature(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }
        String text = expression.trim();
        // 区间 [1..10]：要同时有 '[' 与 '..' 才是区间，
        // 光有 '[' 更可能是数组下标（那在本仓 EL 里是支持的）
        if (text.contains("[") && text.contains("..")) {
            return "区间表达式 [a..b]";
        }
        if (text.contains("@\"") || text.contains("@'") || text.startsWith("@")) {
            return "上下文引用 @\"...\"";
        }
        String[] feelFunctions = {"date(", "time(", "duration(", "date and time(",
                "not(", "list contains(", "every ", "some ", "instance of("};
        for (String marker : feelFunctions) {
            if (text.contains(marker)) {
                return "内置函数 " + marker.replace("(", "").trim();
            }
        }
        if (text.contains("function ")) {
            return "函数定义 function";
        }
        return null;
    }
}