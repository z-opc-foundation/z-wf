package com.zifang.z.wf.core.definition;

/**
 * 流程定义相关异常（解析 / 部署 / 校验失败）。
 *
 * @author zifang
 */
public class WfDefinitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WfDefinitionException(String message) {
        super(message);
    }

    public WfDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
