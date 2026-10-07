package com.zifang.z.wf.web.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.model.WfProcessInstance;
import com.zifang.z.wf.core.service.WfExternalTaskService;
import com.zifang.z.wf.core.view.WfExternalTaskView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 外部任务 —— 流程里交给外部 worker 做的那一步。
 *
 * <p>基址 {@code /api/wf/external-tasks}。
 *
 * <p><b>为什么不并进 {@code WfTaskController}</b>：认领待办与领外部活看起来都是"拿件活来做"，
 * 但语义相反。待办的领取是<b>互斥且立即</b>的（认领完别人就看不到了），
 * 外部活的领取是<b>租约制</b>（到期会被重新领走），且两者落在不同的载体上
 * （WfTask vs WfJob）。混在一起迟早有人按待办的直觉去调外部接口 ——
 * 少了租约，于是 worker 崩了流程就永远卡住。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/external-tasks")
@Tag(name = "006_外部任务")
public class WfExternalTaskController {

    @Resource
    private WfExternalTaskService externalTaskService;

    @PostMapping("/fetch")
    @Operation(summary = "001_按主题领活（原子选出+上锁，租约制）")
    public Result<List<WfExternalTaskView>> fetch(@RequestBody FetchRequest request) {
        if (request == null) {
            throw new com.zifang.z.wf.core.service.WfEngineException("请求体不能为空");
        }
        return Result.success(externalTaskService.fetchAndLock(request.getTopic(),
                request.getWorkerId(), request.getMaxTasks() == null ? 10 : request.getMaxTasks(),
                request.getLeaseMillis() == null ? 0L : request.getLeaseMillis()));
    }

    @PostMapping("/{taskId}/complete")
    @Operation(summary = "002_交差：把外部结果交给流程，token 继续往下走")
    public Result<Map<String, Object>> complete(@PathVariable String taskId,
                                                @RequestBody CompleteRequest request) {
        if (request == null) {
            throw new com.zifang.z.wf.core.service.WfEngineException("请求体不能为空");
        }
        WfProcessInstance instance = externalTaskService.complete(taskId,
                request.getWorkerId(), request.getVariables());
        return Result.success(toInstanceView(instance));
    }

    @PostMapping("/{taskId}/fail")
    @Operation(summary = "003_报失败：解锁并扣重试（失败多半是瞬时的，不解锁等于永久卡死）")
    public Result<Void> fail(@PathVariable String taskId, @RequestBody FailRequest request) {
        if (request == null) {
            throw new com.zifang.z.wf.core.service.WfEngineException("请求体不能为空");
        }
        externalTaskService.fail(taskId, request.getWorkerId(), request.getErrorMessage(),
                request.getRetryDelayMillis() == null ? 0L : request.getRetryDelayMillis());
        return Result.success();
    }

    @PostMapping("/{taskId}/bpmn-error")
    @Operation(summary = "004_报错交回：报一个带错误码的业务错误，流程走错误边界"
            + "（与 fail 的区别是「确定失败」对「再试一次」）")
    public Result<Map<String, Object>> bpmnError(@PathVariable String taskId,
                                                 @RequestBody BpmnErrorRequest request) {
        if (request == null) {
            throw new com.zifang.z.wf.core.service.WfEngineException("请求体不能为空");
        }
        WfProcessInstance instance = externalTaskService.handleBpmnError(taskId,
                request.getWorkerId(), request.getErrorCode(), request.getErrorMessage(),
                request.getVariables());
        return Result.success(toInstanceView(instance));
    }

    @PostMapping("/{taskId}/release")
    @Operation(summary = "005_主动释放：解锁但不扣重试（如部署回滚、认错 topic）")
    public Result<Void> release(@PathVariable String taskId, @RequestParam String workerId) {
        externalTaskService.release(taskId, workerId);
        return Result.success();
    }

    @GetMapping
    @Operation(summary = "006_查外部任务（管理端/排障）")
    public Result<List<WfExternalTaskView>> list(@RequestParam(required = false) String topic,
                                                 @RequestParam(required = false) Integer pageNum,
                                                 @RequestParam(required = false) Integer pageSize) {
        return Result.success(externalTaskService.listTasks(topic, pageNum, pageSize));
    }

    @GetMapping("/count")
    @Operation(summary = "007_某主题积压了多少活")
    public Result<Long> count(@RequestParam(required = false) String topic) {
        return Result.success(externalTaskService.countTasks(topic));
    }

    @GetMapping("/locked")
    @Operation(summary = "008_某个 worker 当前锁着哪些活")
    public Result<List<WfExternalTaskView>> locked(@RequestParam String workerId,
                                                   @RequestParam(required = false) String topic) {
        return Result.success(externalTaskService.listLockedBy(topic, workerId));
    }

    /**
     * 只回摘要而不是整个 {@link WfProcessInstance}。
     *
     * <p>实例实体里带 variables（随业务时长不断膨胀）与 revision 等内部字段，
     * 塞进每次交差的响应里会让响应体随流程跑的时间变大，而 worker 交差后
     * 真正需要的是"我这一步过了没有、整单到哪了"。
     */
    private Map<String, Object> toInstanceView(WfProcessInstance instance) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("processInstanceId", instance.getId());
        view.put("definitionKey", instance.getDefinitionKey());
        view.put("definitionVersion", instance.getDefinitionVersion());
        view.put("businessKey", instance.getBusinessKey());
        view.put("status", instance.getStatus() == null ? null : instance.getStatus().name());
        // 终态判定走状态枚举上的 isTerminal()，实例自身没有这个方法
        view.put("terminal", instance.getStatus() != null && instance.getStatus().isTerminal());
        return view;
    }

    /** 领活请求。 */
    public static class FetchRequest {
        private String topic;
        private String workerId;
        private Integer maxTasks;
        private Long leaseMillis;

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public Integer getMaxTasks() {
            return maxTasks;
        }

        public void setMaxTasks(Integer maxTasks) {
            this.maxTasks = maxTasks;
        }

        public Long getLeaseMillis() {
            return leaseMillis;
        }

        public void setLeaseMillis(Long leaseMillis) {
            this.leaseMillis = leaseMillis;
        }
    }

    /** 交差请求。 */
    public static class CompleteRequest {
        private String workerId;
        private Map<String, Object> variables;

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables;
        }
    }

    /**
     * 报错交回请求（第 41 轮）。
     *
 * <p>{@code errorCode} 是<b>必填的</b>：没有它就没有边界事件能捕获，
     * 引擎会直接拒绝这次调用而不是"随便找个边界" ——
     * 后者会让流程走到一条与失败原因无关的分支上，而从外面看完全正常。
 */
    public static class BpmnErrorRequest {
        private String workerId;
        private String errorCode;
        private String errorMessage;
        private Map<String, Object> variables;

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public void setErrorCode(String errorCode) {
            this.errorCode = errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables;
        }
    }

    /** 失败上报请求。 */
    public static class FailRequest {
        private String workerId;
        private String errorMessage;
        private Long retryDelayMillis;

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public Long getRetryDelayMillis() {
            return retryDelayMillis;
        }

        public void setRetryDelayMillis(Long retryDelayMillis) {
            this.retryDelayMillis = retryDelayMillis;
        }
    }
}
