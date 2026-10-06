package com.zifang.z.wf.core.definition.dmn;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 一个已部署的 DMN 决策定义 —— 流程引擎里的"决策"。
 *
 * <p><b>一个 {@code <decision>} 就是一条可部署的决策</b>，key 取 {@code <decision id>}：
 * 同一份 DMN 文件里可以有多个 decision，各自独立部署与求值
 * （与 Camunda 的 {@code DecisionService#evaluateDecisionByKey} 同形）。
 *
 * <p><b>既存解析后的表，也存 XML 原文</b>，与 {@code WfDefinition} 同一套范式：
 * 前者供求值（每次 businessRuleTask 都要跑一遍，不该重复解析），
 * 后者供回读与排障（表被改坏时要能看见原文长什么样）。
 * 只存其中一样都不够：只存 XML ⇒ 每次求值重解析，且"解析行为"会随实现漂移；
 * 只存结构 ⇒ 出问题没人能对照原文。
 *
 * @author zifang
 */
public class WfDmnDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 决策 key（DMN {@code <decision id>}），全局唯一。 */
    private String key;

    /** 决策名称（{@code <decision name>}），为空时回落到 key。 */
    private String name;

    /** 版本号。同一 key 重复部署 ⇒ 版本 +1，<b>旧版本保留</b>。 */
    private int version;

    /** DMN XML 原文。 */
    private String dmnXml;

    /** 解析出的决策表。一个 decision 在本实现里恰好一张表。 */
    private WfDmnTable table;

    /** 部署时间。 */
    private Date deployTime;

    public WfDmnDecision() {
    }

    public WfDmnDecision(String key, String name) {
        this.key = key;
        this.name = name;
    }

    /**
     * 取决策表；没有解析结果时返回 {@code null}。
     *
     * <p>返回 null 而不是抛异常：部署期校验器要靠它把"这个决策没有表"报成 ERROR，
     * 而求值期拿到 null 则说明绕过了部署 —— 两边要看到的形状一样。
     */
    public WfDmnTable table() {
        return table;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getDmnXml() {
        return dmnXml;
    }

    public void setDmnXml(String dmnXml) {
        this.dmnXml = dmnXml;
    }

    public WfDmnTable getTable() {
        return table;
    }

    public void setTable(WfDmnTable table) {
        this.table = table;
    }

    public Date getDeployTime() {
        return deployTime;
    }

    public void setDeployTime(Date deployTime) {
        this.deployTime = deployTime;
    }

    @Override
    public String toString() {
        return "WfDmnDecision{key=" + key + ", version=" + version
                + ", hitPolicy=" + (table == null ? "?" : table.getHitPolicy())
                + ", rules=" + (table == null ? 0 : table.getRules().size()) + "}";
    }

    // ==================== 决策表 ====================

    /**
     * 一张决策表。
     *
     * <p>结构刻意保持扁平：输入是一串表达式、输出是一串具名列、规则是一串
     * "输入项 + 输出项"。决策表的价值在于<b>一眼能读懂规则之间的优先级关系</b>，
     * 而这依赖规则在表内的<b>先后次序</b> —— 所以 {@link #rules} 是 {@link List}
     * 不是 {@link Set}，{@code FIRST} 与 {@code RULE_ORDER} 的结果都直接由它决定。
     */
    public static class WfDmnTable implements Serializable {

        private static final long serialVersionUID = 1L;

        private String id;

        /** 命中策略。解析期已补默认值（{@code UNIQUE}），这里<b>不会为 null</b>。 */
        private WfDmnHitPolicy hitPolicy = WfDmnHitPolicy.UNIQUE;

        /** COLLECT 的聚合器（SUM / MIN / MAX / COUNT）；为空表示不聚合。 */
        private String aggregator;

        /** 每列输入的取值表达式，顺序与规则里的 {@code inputEntry} 一一对应。 */
        private List<String> inputExpressions = new ArrayList<>();

        /** 输出列。 */
        private List<WfDmnOutput> outputs = new ArrayList<>();

        /** 规则。顺序是契约的一部分。 */
        private List<WfDmnRule> rules = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public WfDmnHitPolicy getHitPolicy() {
            return hitPolicy;
        }

        public void setHitPolicy(WfDmnHitPolicy hitPolicy) {
            this.hitPolicy = hitPolicy;
        }

        public String getAggregator() {
            return aggregator;
        }

        public void setAggregator(String aggregator) {
            this.aggregator = aggregator;
        }

        public List<String> getInputExpressions() {
            return inputExpressions;
        }

        public void setInputExpressions(List<String> inputExpressions) {
            this.inputExpressions = inputExpressions;
        }

        public List<WfDmnOutput> getOutputs() {
            return outputs;
        }

        public void setOutputs(List<WfDmnOutput> outputs) {
            this.outputs = outputs;
        }

        public List<WfDmnRule> getRules() {
            return rules;
        }

        public void setRules(List<WfDmnRule> rules) {
            this.rules = rules;
        }
    }

    /**
     * 一个输出列。
     *
     * <p>{@link #outputValues} 是可选的<b>有序值列表</b>，它有两个作用：
     * ① {@code OUTPUT_PRIORITY} 按它在列表里的次序定优先级（第 0 位最高）；
     * ② 求值时把命中的输出值展开成<b>完整列表</b>而不是裸字符串 ——
     * 这条是 DMN 的标准行为，展开后 {@code "silver"} 变成 {@code ["silver"]}，
     * 调用方不用再为"它是单值还是列表"写分支。
     */
    public static class WfDmnOutput implements Serializable {

        private static final long serialVersionUID = 1L;

        private String name;

        private String typeRef;

        private List<String> outputValues = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTypeRef() {
            return typeRef;
        }

        public void setTypeRef(String typeRef) {
            this.typeRef = typeRef;
        }

        public List<String> getOutputValues() {
            return outputValues;
        }

        public void setOutputValues(List<String> outputValues) {
            this.outputValues = outputValues;
        }
    }

    /**
     * 一行规则：若干输入项 + 若干输出项。
     *
     * <p>输入项为 {@code "-"}（DMN 的"不关心"）或空白时，该列<b>恒成立</b>。
     * 这一点很容易被写成"取反"或"报错"，而它其实是最常用的一列
     * （"GOLD 且金额不限"就是靠它）。
     */
    public static class WfDmnRule implements Serializable {

        private static final long serialVersionUID = 1L;

        private List<String> inputEntries = new ArrayList<>();

        private List<String> outputEntries = new ArrayList<>();

        public List<String> getInputEntries() {
            return inputEntries;
        }

        public void setInputEntries(List<String> inputEntries) {
            this.inputEntries = inputEntries;
        }

        public List<String> getOutputEntries() {
            return outputEntries;
        }

        public void setOutputEntries(List<String> outputEntries) {
            this.outputEntries = outputEntries;
        }
    }
}