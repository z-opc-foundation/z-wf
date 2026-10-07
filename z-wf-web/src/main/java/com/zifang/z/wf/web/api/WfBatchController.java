package com.zifang.z.wf.web.api;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.model.WfBatch;
import com.zifang.z.wf.core.model.WfBatchElement;
import com.zifang.z.wf.core.model.WfBatchOperation;
import com.zifang.z.wf.core.model.WfBatchCriteria;
import com.zifang.z.wf.core.persistence.WfBatchQuery;
import com.zifang.z.wf.core.service.WfBatchService;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.view.WfBatchView;
import com.zifang.z.wf.web.dto.WfRequests;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 批量操作 Controller —— Camunda 的 {@code Batch}。
 *
 * <p>基址 {@code /api/wf/batches}。
 *
 * <p><b>它补的是哪一块</b>：数据迁移。旧流程用 {@code approved} 布尔判断、
 * 新流程要读 {@code status} 枚举时，一次要把十万个在跑的实例改过来 ——
 * 逐个开接口改是不可能的，而它们此刻还在被人正常办理。
 *
 * <p><b>{@code POST} 只创建、不动手</b>，这一点在端点层面就要立住：
 * 创建与执行是两个不同的 URL、两次不同的调用，
 * 「我只是想看看这批会命中多少」不会顺手把数据改了。
 * 确认的入口是 {@code GET /{id}/count}。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/batches")
@Tag(name = "020_批量操作")
public class WfBatchController {

    @Resource
    private WfBatchService batchService;

    @GetMapping
    @Operation(summary = "001_列批次（可按类型 / 状态 / 是否挂起 / 创建人筛）")
    public Result<Map<String, Object>> list(@RequestParam(required = false) String batchType,
                                            @RequestParam(required = false) String state,
                                            @RequestParam(required = false) Boolean suspended,
                                            @RequestParam(required = false) String operatorId,
                                            @RequestParam(required = false) Integer pageNum,
                                            @RequestParam(required = false) Integer pageSize) {
        WfBatchQuery query = buildQuery(batchType, state, suspended, operatorId);
        if (pageNum != null) {
            query.setPageNum(pageNum);
        }
        if (pageSize != null) {
            query.setPageSize(pageSize);
        }
        List<WfBatchView> views = new ArrayList<>();
        for (WfBatch batch : batchService.queryBatches(query)) {
            views.add(toView(batch));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("records", views);
        payload.put("total", batchService.countBatches(query));
        return Result.success(payload);
    }

    @GetMapping("/{batchId}")
    @Operation(summary = "002_取一个批次")
    public Result<WfBatchView> get(@PathVariable String batchId) {
        return Result.success(toView(requireBatch(batchId)));
    }

    /**
     * <b>执行前的那一眼</b>：现在跑这批会命中多少个目标。
     *
     * <p>只数不落，所以可以随便调。
     */
    @GetMapping("/{batchId}/count")
    @Operation(summary = "003_看这批会命中多少个目标（只数，不改任何东西）")
    public Result<Map<String, Object>> count(@PathVariable String batchId) {
        requireBatch(batchId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", batchService.countTargets(batchId));
        return Result.success(payload);
    }

    @GetMapping("/{batchId}/elements")
    @Operation(summary = "004_看这批处理了哪些目标（failedOnly=true 时只看失败的）")
    public Result<WfBatchView.WfBatchElementList> elements(
            @PathVariable String batchId,
            @RequestParam(required = false, defaultValue = "false") boolean failedOnly) {
        requireBatch(batchId);
        List<WfBatchElement> rows = failedOnly
                ? batchService.listFailedElements(batchId)
                : batchService.listElements(batchId);
        List<WfBatchView.WfBatchElementView> views = new ArrayList<>();
        for (WfBatchElement element : rows) {
            views.add(toElementView(element));
        }
        WfBatchView.WfBatchElementList list = new WfBatchView.WfBatchElementList();
        list.setBatchId(batchId);
        list.setElements(views);
        list.setTotal(views.size());
        return Result.success(list);
    }

    @PostMapping
    @Operation(summary = "005_创建批次（**什么都不改**，只是把「改哪些」「改什么」记下来）")
    public Result<WfBatchView> create(@RequestBody WfRequests.BatchOperation request) {
        if (request == null) {
            throw new WfEngineException("请求体不能为空");
        }
        List<WfBatchOperation> operations = toOperations(request.getOperations());
        return Result.success(toView(batchService.createBatch(
                parseType(request.getBatchType()), toCriteria(request.getCriteria()),
                operations, request.getOperatorId())));
    }

    @PostMapping("/{batchId}/execute")
    @Operation(summary = "006_执行批次（逐个目标独立成败；批次不做二次执行）")
    public Result<WfBatchView> execute(@PathVariable String batchId,
                                        @RequestParam(required = false) Long now) {
        return Result.success(toView(batchService.executeBatch(batchId,
                now == null ? new Date() : new Date(now))));
    }

    @PostMapping("/{batchId}/suspend")
    @Operation(summary = "007_挂起批次（只对还没执行的批次有效）")
    public Result<WfBatchView> suspend(@PathVariable String batchId,
                                       @RequestParam(required = false) String operatorId) {
        return Result.success(toView(batchService.suspendBatch(batchId, operatorId)));
    }

    @PostMapping("/{batchId}/activate")
    @Operation(summary = "008_激活批次")
    public Result<WfBatchView> activate(@PathVariable String batchId,
                                        @RequestParam(required = false) String operatorId) {
        return Result.success(toView(batchService.activateBatch(batchId, operatorId)));
    }

    @DeleteMapping("/{batchId}")
    @Operation(summary = "009_删批次（连带删掉它的全部明细）")
    public Result<Void> delete(@PathVariable String batchId) {
        if (!batchService.deleteBatch(batchId)) {
            // 不静默返回成功：调用方以为删掉了、实际没删，下次列出来还在，
            // 而"我明明删了它"是排障时最难自己解释的一种现象
            throw new WfEngineException("批次不存在: " + batchId);
        }
        return Result.success();
    }

    // ==================== 入参转换 ====================

    /**
     * 列表与详情<b>分开两条校验路径也没关系</b>：它们各自只解析自己那几个字段，
     * 且都走同一套 {@link #parseType} / {@code valueOf} 的"不认得就报错"。
     */
    private WfBatchQuery buildQuery(String batchType, String state, Boolean suspended,
                                    String operatorId) {
        WfBatchQuery query = new WfBatchQuery().setOperatorId(operatorId);
        if (batchType != null && !batchType.trim().isEmpty()) {
            query.setBatchType(parseType(batchType));
        }
        if (state != null && !state.trim().isEmpty()) {
            WfBatch.State parsed;
            try {
                parsed = WfBatch.State.valueOf(state.trim());
            } catch (RuntimeException e) {
                throw new WfEngineException("未知的批次状态 [" + state + "]。合法值: CREATED / EXECUTING / COMPLETED / FAILED");
            }
            query.setState(parsed);
        }
        if (suspended != null) {
            query.setSuspendedOnly(suspended);
        }
        return query;
    }

    private WfBatch requireBatch(String batchId) {
        WfBatch batch = batchService.getBatch(batchId);
        if (batch == null) {
            throw new WfEngineException("批次不存在: " + batchId);
        }
        return batch;
    }

    private WfBatch.Type parseType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new WfEngineException("批次类型不能为空。合法值: INSTANCE / TASK / JOB");
        }
        // fromName 内部对不认得的值会抛，并把合法值一并列出来
        return WfBatch.Type.fromName(raw);
    }

    private List<WfBatchOperation> toOperations(List<WfRequests.BatchAction> actions) {
        if (actions == null || actions.isEmpty()) {
            throw new WfEngineException("批次至少要有一条操作");
        }
        List<WfBatchOperation> operations = new ArrayList<>();
        for (WfRequests.BatchAction action : actions) {
            if (action == null) {
                throw new WfEngineException("操作不能为 null");
            }
            WfBatchOperation.Type type = WfBatchOperation.Type.fromName(action.getType());
            if (type == null) {
                throw new WfEngineException("操作类型不能为空");
            }
            WfBatchOperation operation = new WfBatchOperation(type);
            operation.setVariable(action.getVariable());
            operation.setValue(action.getValue());
            if (action.getVariables() != null) {
                operation.setVariables(new java.util.TreeMap<>(action.getVariables()));
            }
            operation.setRetries(action.getRetries());
            operation.setPriority(action.getPriority());
            operation.setReason(action.getReason());
            operations.add(operation);
        }
        return operations;
    }

    /**
     * 请求 DTO → 引擎的条件对象。
     *
     * <p><b>逐字段搬，不做"整包塞进去"</b>：后者看着省事，
     * 但 DTO 以后加一个字段就会自动变成一个筛选条件 ——
     * 而「多一个筛选维度」在批量改写里是危险方向的默认值。
     */
    private WfBatchCriteria toCriteria(WfRequests.BatchCriteriaRequest request) {
        if (request == null) {
            throw new WfEngineException("筛选条件不能为空 —— 不给条件等于「改这个类型下的全部」");
        }
        WfBatchCriteria criteria = new WfBatchCriteria();
        if (request.getIds() != null) {
            criteria.setIds(new ArrayList<>(request.getIds()));
        }
        criteria.setProcessDefinitionKey(request.getProcessDefinitionKey());
        criteria.setProcessDefinitionVersion(request.getProcessDefinitionVersion());
        criteria.setProcessBusinessKey(request.getProcessBusinessKey());
        criteria.setProcessStartUserId(request.getProcessStartUserId());
        criteria.setProcessCategory(request.getProcessCategory());
        criteria.setStatus(request.getStatus());
        criteria.setFinishedOnly(request.getFinishedOnly());
        criteria.setUnfinishedOnly(request.getUnfinishedOnly());
        criteria.setProcessStartTimeFrom(toDate(request.getProcessStartTimeFrom()));
        criteria.setProcessStartTimeTo(toDate(request.getProcessStartTimeTo()));
        criteria.setProcessResult(request.getProcessResult());
        criteria.setTaskProcessInstanceId(request.getTaskProcessInstanceId());
        criteria.setTaskDefinitionId(request.getTaskDefinitionId());
        criteria.setTaskAssignee(request.getTaskAssignee());
        criteria.setTaskOwner(request.getTaskOwner());
        criteria.setTaskCategory(request.getTaskCategory());
        criteria.setTaskStatus(request.getTaskStatus());
        criteria.setTaskOpenOnly(request.getTaskOpenOnly());
        criteria.setTaskUnassignedOnly(request.getTaskUnassignedOnly());
        criteria.setTaskCreateTimeFrom(toDate(request.getTaskCreateTimeFrom()));
        criteria.setTaskCreateTimeTo(toDate(request.getTaskCreateTimeTo()));
        criteria.setJobProcessInstanceId(request.getJobProcessInstanceId());
        criteria.setJobElementId(request.getJobElementId());
        criteria.setJobType(request.getJobType());
        criteria.setJobDueBefore(toDate(request.getJobDueBefore()));
        criteria.setJobRetriesExhausted(request.getJobRetriesExhausted());
        criteria.setJobTopic(request.getJobTopic());
        return criteria;
    }

    private Date toDate(Long epochMillis) {
        return epochMillis == null ? null : new Date(epochMillis);
    }

    // ==================== 视图 ====================

    private WfBatchView toView(WfBatch batch) {
        WfBatchView view = new WfBatchView();
        view.setId(batch.getId());
        view.setBatchType(batch.getBatchType() == null ? null : batch.getBatchType().name());
        view.setState(batch.getState() == null ? null : batch.getState().name());
        view.setStateLabel(batch.getState() == null ? null : batch.getState().getLabel());
        view.setCriteria(batch.getCriteria());
        view.setOperations(batch.getOperations());
        view.setAffectedCount(batch.getAffectedCount());
        view.setFailureCount(batch.getFailureCount());
        view.setCreateTime(batch.getCreateTime());
        view.setStartTime(batch.getStartTime());
        view.setEndTime(batch.getEndTime());
        view.setSuspended(batch.isSuspended());
        view.setOperatorId(batch.getOperatorId());
        view.setFailureReason(batch.getFailureReason());
        return view;
    }

    private WfBatchView.WfBatchElementView toElementView(WfBatchElement element) {
        WfBatchView.WfBatchElementView view = new WfBatchView.WfBatchElementView();
        view.setId(element.getId());
        view.setTargetId(element.getTargetId());
        view.setState(element.getState() == null ? null : element.getState().name());
        view.setStateLabel(element.getState() == null ? null : element.getState().getLabel());
        view.setFailureMessage(element.getFailureMessage());
        view.setHandledAt(element.getHandledAt());
        return view;
    }
}