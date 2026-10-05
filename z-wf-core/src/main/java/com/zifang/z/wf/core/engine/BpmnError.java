package com.zifang.z.wf.core.engine;

/**
 * BPMN 业务错误 —— 由 {@code serviceTask} 的 delegate 抛出，走补偿/清理分支。
 *
 * <p><b>它与普通异常的区别是"有码"</b>：{@code errorCode} 决定了能被哪个
 * 边界事件捕获。普通异常没有码，引擎无从判断该不该路由，只能让流程失败。
 *
 * <p>用法：
 * <pre>{@code
 * public void execute(WfContext context, WfExecution execution) {
 *     try {
 *         erpClient.createOrder(...);
 *     } catch (ErpDownException e) {
 *         throw new BpmnError("ERP_UNAVAILABLE", "ERP 不可用: " + e.getMessage());
 *     }
 * }
 * }</pre>
 * 流程定义里配一个挂在该 serviceTask 上、`errorRef="ERP_UNAVAILABLE"` 的
 * boundaryEvent，错误就会沿它的出线走到补偿分支。
 *
 * <p>没有匹配的边界事件时，流程以"内部终止 + 记录错误码"收场，
 * 而不是静默继续 —— 这一点与本仓其它地方一致。
 *
 * @author zifang
 */
public class BpmnError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public BpmnError(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BpmnError(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
