package com.zifang.z.wf.core.definition;

import java.io.Serializable;

/**
 * 流程定义校验问题。
 *
 * <p>{@link Severity#WARN} 不阻断部署（只记录），{@link Severity#ERROR} 阻断。
 *
 * @author zifang
 */
public class WfValidationIssue implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 严重级别。
     */
    public enum Severity {
        /** 阻断部署。 */
        ERROR,
        /** 仅告警，部署继续。 */
        WARN
    }

    private final Severity severity;
    private final String nodeId;
    private final String message;

    public WfValidationIssue(Severity severity, String nodeId, String message) {
        this.severity = severity;
        this.nodeId = nodeId;
        this.message = message;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "[" + severity + "]" + (nodeId != null ? " " + nodeId + ":" : " ") + " " + message;
    }
}
