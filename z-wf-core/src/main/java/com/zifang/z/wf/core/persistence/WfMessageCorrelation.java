package com.zifang.z.wf.core.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

import com.zifang.z.wf.core.service.WfEngineException;

/**
 * 消息关联条件（对应 Camunda 的 {@code MessageCorrelationBuilder}）。
 *
 * <p>存在的理由：{@code triggerMessage(messageName, processInstanceId, …)}
 * 要求调用方<b>已经知道</b>是哪一条流程实例 —— 而"收到 ERP 的回执，发一条
 * {@code erpDone} 消息，引擎自己去找到那条在等它、且变量对得上的单"
 * 才是消息驱动集成的常态。调用方手里通常只有业务键与业务字段，
 * 让它先反查流程实例再调触发，等于把"找得到"这件事从引擎挪回了业务方。
 *
 * <p><b>所有条件都是「与」，不设条件的字段一律不参与匹配</b>。
 * 刻意不提供 `withoutVariables()` 这种开关：不给条件就是不给条件，
 * 多一个"显式说不校验"的开关只会让人以为默认是要校验的。
 *
 * <p><b>不给任何条件时，本条件等价于"只按消息名找"</b>，
 * 此时若匹配到多条会报错（见 {@code WfRuntimeService#correlate}）——
 * 点对点消息必须能决定触发哪一个，这与 {@code triggerMessage} 是同一条规则。
 *
 * @author zifang
 */
public class WfMessageCorrelation {

    private String messageName;

    private String processInstanceId;

    private String businessKey;

    private String definitionKey;

    /**
     * 流程级变量相等匹配；键不在流程变量里算不匹配（不等于「值为 null」）。
     *
     * <p><b>只用于匹配，关联成功后不会回写流程变量</b>。
     * 理由见 {@code WfRuntimeService#correlate} 的方法注释：
     * 三类候选的触发入口并不都接受变量，只让三分之一的路径产生副作用
     * 会让同一次请求因命中形态不同而结果不同。
     */
    private final Map<String, Object> variables = new LinkedHashMap<>();

    /** 执行级变量相等匹配；只在并行分支上有意义。 */
    private final Map<String, Object> localVariables = new LinkedHashMap<>();

    public WfMessageCorrelation() {
    }

    public WfMessageCorrelation(String messageName) {
        this.messageName = messageName;
    }

    public String getMessageName() {
        return messageName;
    }

    public WfMessageCorrelation setMessageName(String messageName) {
        this.messageName = messageName;
        return this;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public WfMessageCorrelation setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
        return this;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public WfMessageCorrelation setBusinessKey(String businessKey) {
        this.businessKey = businessKey;
        return this;
    }

    public String getDefinitionKey() {
        return definitionKey;
    }

    public WfMessageCorrelation setDefinitionKey(String definitionKey) {
        this.definitionKey = definitionKey;
        return this;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public WfMessageCorrelation setVariables(Map<String, Object> vars) {
        this.variables.clear();
        if (vars != null) {
            this.variables.putAll(vars);
        }
        return this;
    }

    public WfMessageCorrelation setVariable(String name, Object value) {
        this.variables.put(name, value);
        return this;
    }

    public Map<String, Object> getLocalVariables() {
        return localVariables;
    }

    public WfMessageCorrelation setLocalVariables(Map<String, Object> vars) {
        this.localVariables.clear();
        if (vars != null) {
            this.localVariables.putAll(vars);
        }
        return this;
    }

    public WfMessageCorrelation setLocalVariable(String name, Object value) {
        this.localVariables.put(name, value);
        return this;
    }

    /** 有没有任何约束 —— 用于区分"没条件"与"条件给错了"。 */
    public boolean hasConditions() {
        return notBlank(processInstanceId) || notBlank(businessKey) || notBlank(definitionKey)
                || !variables.isEmpty() || !localVariables.isEmpty();
    }

    /** 条件的可读形式，只给诊断信息用。 */
    public String describe() {
        StringBuilder text = new StringBuilder();
        if (notBlank(processInstanceId)) {
            text.append("processInstanceId=").append(processInstanceId);
        }
        if (notBlank(businessKey)) {
            append(text, "businessKey=" + businessKey);
        }
        if (notBlank(definitionKey)) {
            append(text, "definitionKey=" + definitionKey);
        }
        if (!variables.isEmpty()) {
            append(text, "变量=" + variables.keySet());
        }
        if (!localVariables.isEmpty()) {
            append(text, "分支变量=" + localVariables.keySet());
        }
        return text.length() == 0 ? "（无附加条件）" : text.toString();
    }

    private static void append(StringBuilder text, String part) {
        if (text.length() > 0) {
            text.append(", ");
        }
        text.append(part);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 消息名为空时直接拒绝。
     *
     * <p>放在条件对象自己身上而不是等到用的时候：消息名是这条关联里唯一的必填项，
     * 漏了它的情况下若继续跑，症状是「匹配到 0 条 → 报『没有等待消息 [] 的接收任务』」，
     * 报错指向的是一个空名字，比直接说"消息名没填"难查得多。
     */
    public void requireMessageName() {
        if (!notBlank(messageName)) {
            throw new WfEngineException("消息名不能为空");
        }
    }
}
