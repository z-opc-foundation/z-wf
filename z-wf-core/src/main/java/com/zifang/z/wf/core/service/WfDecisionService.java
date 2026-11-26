package com.zifang.z.wf.core.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.definition.dmn.WfDecisionDependency;
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
 * <p><b>支持决策图。</b>决策图（{@code informationRequirement} 里
 * {@code requiredDecision} 组成的有向无环图）在第 35 轮补齐：求值时
 * <b>按依赖关系递归展开</b>，每个上游决策的 {@code <output name>} 作为它的输出值
 * 进入下游决策的求值上下文。
 *
 * <p><b>为什么必须是"递归展开"而不是"按声明顺序跑一遍"</b>：
 * 依赖顺序与声明顺序只要不一致一次，按声明顺序就会在<b>上游还没算出来</b>的时候
 * 去算下游 —— 那张下游表读到的是缺输入，按本仓 fail-closed 约定于是
 * <b>一条规则都不命中</b>，结果是空，且没有任何报错。
 * 递归展开让声明顺序彻底不再影响结果（判据见 {@code WfDmnDecisionGraphTest} 里
 * "依赖写在前面"那条）。第 23 轮正是为了不做这个才在部署期直接拒绝决策图。
 *
 * <p><b>但决策图不支持文字表达式</b>（DMN 的 literal expression）：
 * 一个不带 {@code <decisionTable>} 的决策节点求不出值，
 * 而放行的症状是"这一跳什么都没算出来" —— 决策图里最难查的那种错。
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
     * <p>也做<b>文件内的环检测</b>：这是不碰持久化就能确定的那部分。
     * 跨文件的环要到 {@link #deployDecision} 才能凑齐整张图 ——
     * 两处的关系见 {@link WfDecisionDependency#findCycle} 的说明。
     *
     * @return 文件里的全部决策（顺序与文件里一致）
     * @throws WfDefinitionException 解析失败、含 FEEL 构造、文件内成环，
     *                               或一个 decision 都没有
     */
    public List<WfDmnDecision> parseDecision(String dmnXml) {
        List<WfDmnDecision> decisions = parser.parse(dmnXml);
        for (WfDmnDecision decision : decisions) {
            guardAgainstFeel(decision);
        }
        List<String> cycle = WfDecisionDependency.findCycle(edgesOf(decisions));
        if (cycle != null) {
            throw new WfDefinitionException("这份 DMN 里的决策依赖成环: "
                    + String.join(" -> ", cycle)
                    + "。成环的图求值时会一直要下一个决策的下游，"
                    + "最后要么栈溢出、要么反复取到算了一半的决策 —— "
                    + "两种都不是报错，而是一张算出了结果的表。"
                    + "请断开环上的一条依赖");
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
     * <p><b>环检测在落库之前做完</b>，而且用的是「本文件 + 库里已部署的边」
     * 凑出来的<b>闭合图</b>。两点都是刻意的：
     * <ul>
     *   <li>先查后存：一份文件里有三个决策，第二个依赖第三个，
     *       而第三个依赖第二个 —— 若边存边查，文件里前两个已经落库了，
     *       而部署整体失败。留下的半套依赖比全不部署更难收拾。</li>
     *   <li>闭包：只查文件内的边会漏掉跨文件的环（A 依赖 B、B 依赖 A 分两个文件），
     *       而那种环单文件测试一条都测不出来。</li>
     * </ul>
     *
     * @return 本次部署产生的全部决策 key（按文件里顺序）
     */
    public List<String> deployDecision(String dmnXml) {
        List<WfDmnDecision> decisions = parseDecision(dmnXml);
        ensureNoLoopInDecisions(decisions);
        List<String> keys = new ArrayList<>();
        for (WfDmnDecision decision : decisions) {
            int version = nextVersion(decision.getKey());
            decision.setVersion(version);
            decision.setDeployTime(new Date());
            persistence.saveDecision(decision);
            log.info("部署决策定义: key={} version={} hitPolicy={} rules={} required={}",
                    decision.getKey(), version, decision.getTable().getHitPolicy(),
                    decision.getTable().getRules().size(), decision.getRequiredDecisions());
            keys.add(decision.getKey());
        }
        return keys;
    }

    /** 一批决策的依赖边（只含这批决策自己声明的）。 */
    private Map<String, List<String>> edgesOf(List<WfDmnDecision> decisions) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (WfDmnDecision decision : decisions) {
            edges.put(decision.getKey(), decision.getRequiredDecisions());
        }
        return edges;
    }

    /**
     * 环检测，范围是<b>本次部署的决策连同它们可达的已部署决策</b>。
     *
     * <p>本批之外的依赖要去库里查，而查不到（没部署）<b>不算环</b>：
     * 依赖可以先于被依赖者部署，那是 DMN 的正常用法（先定需求图，
     * 后补实现）。缺依赖这件事由求值期报错，那时它才有办法说清
     * 「这个 key 确实没部署过，可以现在部署它」。
     */
    private void ensureNoLoopInDecisions(List<WfDmnDecision> incoming) {
        Map<String, List<String>> edges = edgesOf(incoming);
        // 起点是本批决策的**依赖**，不是本批决策的 key。
        // 本批的 key 在 edges 里已经有了，它们的依赖也得往下走 ——
        // 只从本批的 key 出发的话，「q 在本文件里、p 在库里」这种跨文件环
        // 永远走不到 p，而 p 恰恰是闭环的那一条边。
        List<String> pending = new ArrayList<>();
        for (List<String> dependencies : edges.values()) {
            pending.addAll(dependencies);
        }
        for (int i = 0; i < pending.size(); i++) {
            String key = pending.get(i);
            if (edges.containsKey(key)) {
                continue;
            }
            WfDmnDecision deployed = persistence.findLatestDecision(key);
            if (deployed == null) {
                // 没部署的依赖不是环（见本方法 javadoc），也不必再往下走
                edges.put(key, new ArrayList<String>());
                continue;
            }
            List<String> dependencies = deployed.getRequiredDecisions();
            edges.put(key, dependencies);
            pending.addAll(dependencies);
        }
        List<String> cycle = WfDecisionDependency.findCycle(edges);
        if (cycle == null) {
            return;
        }
        throw new WfDefinitionException("决策依赖成环: " + String.join(" -> ", cycle)
                + "。成环的图无法求值 —— 每一跳都要等下一个决策算完，"
                + "而最后一个又回头要第一个。"
                + "在它落库之前挡住，是因为成环的图可能已经被别的流程定义引用，"
                + "症状会变成「某天某个流程跑到这张决策就卡住」，与部署它的那次操作毫无关系。"
                + "请断开环上的一条依赖");
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

    /**
     * 对一个已加载的决策求值 —— 循环里复用同一个决策时省掉每次查库。
     *
     * <p><b>返回的只是本决策的结果</b>，不含上游：上游的输出按 DMN 语义进入
     * <b>求值上下文</b>（供本决策的输入列引用），而不是并进结果 ——
     * 结果表的行数与命中数都必须属于本决策，
     * 否则调用方拿到 {@code matchedRuleCount} 会以为下游表命中了这么多条。
     *
     * <p>返回的对象是 {@link WfDmnDecisionResult}，其中的行来自本决策。
     */
    public WfDmnDecisionResult evaluate(WfDmnDecision decision, Map<String, Object> variables) {
        if (decision == null || decision.getTable() == null) {
            throw new WfEngineException("决策 ["
                    + (decision == null ? "?" : decision.getKey())
                    + "] 没有决策表。部署期已经挡掉了没有表的情况，"
                    + "走到这里说明部署被绕过了（自定义装配，或直接调引擎）");
        }
        List<String> stack = new ArrayList<>();
        // 种子放本决策自己：这样"决策依赖自己"能在第一跳就被认出来，
        // 而不是先白算一级上游再在下一级发现（报错里的路径也就少一跳）
        stack.add(decision.getKey());
        return evaluator.evaluate(decision.getTable(),
                upstreamContext(decision, variables, new LinkedHashMap<String, WfDmnDecisionResult>(), stack),
                decision.getKey());
    }

    // ==================== 决策图：按依赖递归展开 ====================

    /**
     * 把一条决策的<b>全部上游决策</b>先求值完，把它们的输出填进求值上下文。
     *
     * <p><b>递归而不是循环</b>：递归天然给出拓扑序，
     * 所以「依赖写在前面还是写在后面」不影响结果。
     * 而按声明顺序跑一遍，在依赖顺序与声明顺序不一致时会算错且不报错
     * （见类注释与 {@code WfDmnDecisionGraphTest} 里那条判据）。
     *
     * <p><b>{@code resolved} 让菱形依赖只算一次</b>：D 同时依赖 B 与 C，
     * B 与 C 又都依赖 A —— 没有它 A 会被算两遍。
     * 决策表是纯函数，两次算结果一样，但 A 若命中多条而违反 UNIQUE，
     * 第二次会再报一次同样的违规，报错信息里的「第几次出现」会变得莫名其妙。
     */
    private Map<String, Object> upstreamContext(WfDmnDecision decision,
                                                Map<String, Object> variables,
                                                Map<String, WfDmnDecisionResult> resolved,
                                                List<String> stack) {
        if (decision.getRequiredDecisions().isEmpty()) {
            // 没有上游时不复制变量表：绝大多数决策走这条路径，
            // 而复制一遍会让"求值没有改动调用方的变量"这件事变得难以验证
            return variables;
        }
        Map<String, Object> context = new LinkedHashMap<>(variables);
        for (String requiredKey : decision.getRequiredDecisions()) {
            WfDmnDecisionResult upstream = resolved.get(requiredKey);
            if (upstream == null) {
                upstream = resolveRequiredDecision(decision, requiredKey, variables, resolved, stack);
            }
            mergeUpstreamOutputs(decision, requiredKey, upstream, variables, context);
        }
        return context;
    }

    private WfDmnDecisionResult resolveRequiredDecision(WfDmnDecision dependent, String requiredKey,
                                                        Map<String, Object> variables,
                                                        Map<String, WfDmnDecisionResult> resolved,
                                                        List<String> stack) {
        if (stack.contains(requiredKey)) {
            // 部署期本该挡掉（deployDecision 查的是闭合图）。
            // 还能走到这里，说明有东西绕过了部署：直接调 saveDecision，
            // 或数据是别处灌进库的。这道防线是<b>运行期</b>的最后一道，
            // 不是对部署期的重复。
            throw new WfEngineException("求值决策 [" + dependent.getKey() + "] 时发现依赖成环: "
                    + String.join(" -> ", stack) + " -> " + requiredKey
                    + "。成环的图会无限递归下去（栈溢出）。"
                    + "部署期已挡过这一种，能走到这里说明部署被绕过了"
                    + "（直接调 saveDecision，或数据由别处灌入）");
        }
        WfDmnDecision required = persistence.findLatestDecision(requiredKey);
        if (required == null) {
            throw new WfEngineException("决策 [" + dependent.getKey() + "] 依赖的决策 ["
                    + requiredKey + "] 不存在（没有任何版本）。"
                    + "key 取自 <decision id>，不是文件名。"
                    + "依赖允许后于被依赖者部署，所以部署期不报这一条 —— "
                    + "确认这个 id 部署过即可");
        }
        if (required.getTable() == null) {
            throw new WfEngineException("上游决策 [" + requiredKey + "] 没有决策表，"
                    + "[" + dependent.getKey() + "] 拿不到它的输出。部署期已挡过这一种");
        }
        stack.add(requiredKey);
        Map<String, Object> context = upstreamContext(required, variables, resolved, stack);
        WfDmnDecisionResult result = evaluator.evaluate(required.getTable(), context, requiredKey);
        stack.remove(stack.size() - 1);
        resolved.put(requiredKey, result);
        return result;
    }

    /**
     * 把一条上游决策的输出并进下游的求值上下文。
     *
     * <p><b>三处不静默</b>，都是"两个来源给出不同答案"那一类：
     * <ol>
     *   <li><b>上游 0 行</b> ⇒ 报错。上游一条规则都没命中时，
     *       放行的症状是"下游读不到输入，于是也一条都不命中"，
     *       最终表现为一张<b>空结果</b>的表，没有任何报错。</li>
     *   <li><b>上游多行</b> ⇒ 报错。多结果灌给下游没有语义，
     *       替作者挑一行就是在编数据。</li>
     *   <li><b>同名变量取值冲突</b> ⇒ 报错。见方法体。</li>
     * </ol>
     */
    private void mergeUpstreamOutputs(WfDmnDecision dependent, String upstreamKey,
                                      WfDmnDecisionResult upstream,
                                      Map<String, Object> given,
                                      Map<String, Object> context) {
        List<WfDmnDecisionResult.Row> rows = upstream.getRows();
        if (rows.isEmpty()) {
            throw new WfEngineException("上游决策 [" + upstreamKey + "] 一条规则都没有命中，"
                    + "[" + dependent.getKey() + "] 拿不到它的输出。"
                    + "不是「上游没有输出列」，是上游自己没匹配上任何规则 —— "
                    + "先查上游那张表与喂给它的变量");
        }
        if (rows.size() > 1) {
            throw new WfEngineException("上游决策 [" + upstreamKey + "] 用策略 "
                    + upstream.getHitPolicy() + " 产出了 " + rows.size() + " 行，"
                    + "而 [" + dependent.getKey() + "] 需要的是一个值。"
                    + "多结果往下游灌没有语义（本实现不替你挑一行）—— "
                    + "要么把上游改成单结果策略（UNIQUE / ANY / FIRST），"
                    + "要么这一步不要放在决策图里");
        }
        for (Map.Entry<String, Object> output : rows.get(0).getOutputs().entrySet()) {
            String name = output.getKey();
            Object value = output.getValue();
            if (given.containsKey(name)) {
                if (!sameValue(given.get(name), value)) {
                    throw new WfEngineException("变量 [" + name + "] 有两个来源且取值不同: "
                            + "调用方传入 " + given.get(name) + "，上游决策 [" + upstreamKey
                            + "] 算出 " + value + "。下游 [" + dependent.getKey() + "] 用哪一个没有答案："
                            + "照上游算会让调用方传的值被无视，照传入算会让这条依赖形同虚设。"
                            + "请去掉其中一个（依赖的作用本来就是由上游供值）");
                }
                continue;
            }
            if (context.containsKey(name)) {
                if (!sameValue(context.get(name), value)) {
                    throw new WfEngineException("两个上游决策都产出了变量 [" + name
                            + "] 且取值不同（[" + context.get(name) + "] 与 "
                            + value + "]），下游 [" + dependent.getKey() + "] 用哪一个没有答案。"
                            + "给两个上游的输出列改个不重名的 name，或让它们合并成同一条依赖");
                }
                continue;
            }
            context.put(name, value);
        }
    }

    /**
     * 两个值是否"同一个"。
     *
     * <p><b>数值按 {@code double} 比</b>：求值出来的 60000 是 {@code Double}，
     * 而调用方传进来的 60000 可能是 {@code Integer}，
     * {@code equals} 判它们不等 —— 于是"其实是同一个数"会被报成冲突，
     * 而作者看到的是一个自己没写过的报错。
     */
    private static boolean sameValue(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number && right instanceof Number) {
            return ((Number) left).doubleValue() == ((Number) right).doubleValue();
        }
        return left.equals(right);
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