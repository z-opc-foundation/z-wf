package com.zifang.z.wf.web.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.definition.WfDefinitionException;
import com.zifang.z.wf.core.persistence.WfOptimisticLockException;
import com.zifang.z.wf.core.persistence.WfPersistenceException;
import com.zifang.z.wf.core.service.WfEngineException;

/**
 * 统一异常转换。
 *
 * <p>HTTP 状态码的映射口径（前端据此决定是否重试）：
 * <table border="1">
 *   <tr><th>异常</th><th>状态码</th><th>前端应做</th></tr>
 *   <tr><td>{@link WfOptimisticLockException}</td><td>409 Conflict</td>
 *       <td>提示"他人正在处理"，刷新后重试</td></tr>
 *   <tr><td>{@link WfPersistenceException}</td><td>503 Service Unavailable</td>
 *       <td>稍后重试（存储故障）</td></tr>
 *   <tr><td>{@link WfEngineException} / {@link WfDefinitionException}</td>
 *       <td>400 Bad Request</td><td>展示 message，不重试</td></tr>
 * </table>
 *
 * <p><b>乐观锁单独给 409 而不是 500</b>：它是"并发"的正常结果，不是系统故障。
 * 笼统按 500 处理会让前端一律弹"系统错误"，用户就不会刷新重试，
 * 而正确做法是提示"该任务已被他人处理"。
 *
 * @author zifang
 */
@RestControllerAdvice
public class WfExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(WfExceptionAdvice.class);

    @ExceptionHandler(WfOptimisticLockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> onOptimisticLock(WfOptimisticLockException e) {
        log.warn("乐观锁冲突: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(WfPersistenceException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> onPersistence(WfPersistenceException e) {
        log.error("持久化故障", e);
        // 不把 SQL 细节回给前端
        return Result.fail("流程存储暂不可用，请稍后重试");
    }

    @ExceptionHandler(WfEngineException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> onEngine(WfEngineException e) {
        log.warn("引擎业务异常: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(WfDefinitionException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> onDefinition(WfDefinitionException e) {
        log.warn("流程定义异常: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> onIllegalArgument(IllegalArgumentException e) {
        return Result.fail(e.getMessage());
    }
}
