package com.zifang.z.wf.web.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.persistence.WfJobQuery;
import com.zifang.z.wf.core.service.WfJobService;
import com.zifang.z.wf.web.dto.WfViews;
import com.zifang.z.wf.web.mapper.WfViewMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Job 的运维入口 —— 查、提前触发。
 *
 * <p>基址 {@code /api/wf/jobs}。
 *
 * <p><b>为什么单独一个控制器而不是并进 {@code WfProcessOperationController}</b>：
 * job 的生命周期由<b>执行器</b>驱动而不是由人驱动，正常情况下没人碰它 ——
 * 只有"还没到期但等不及了"这种场景才需要人插手，而那种操作会**改变流程的时序**。
 * <p>混进日常操作端点里，它看起来就像一次普通的"办结"，
 * 于是有人会拿它当"让流程往下走"的通用开关用。
 *
 * <p><b>复用 {@code WfViews.JobView} 而不是另建一个 job 视图</b>：
 * 同一实体两个视图、两个 id 字段名（{@code id} 与 {@code jobId}），
 * 调用方从这边取到的 id 拿到那边去查会得到 null ——
 * 而症状是"列表里明明有，点进去却是空的"，很难联想到是字段名不一致。
 * 时间字段也因此沿用既有的 epoch 毫秒约定，而不是把 {@code Date} 直接序列化
 * （那会让时区与毫秒格式变成对外契约的一部分）。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/jobs")
@Tag(name = "013_Job 运维")
public class WfJobController {

    @Resource
    private WfJobService jobService;

    @Resource
    private WfViewMapper viewMapper;

    @PostMapping("/{jobId}/trigger")
    @Operation(summary = "001_提前触发一条 job（不等它到期）")
    public Result<Map<String, Object>> trigger(@PathVariable String jobId,
                                               @RequestParam(required = false) String userId) {
        boolean fired = jobService.triggerJob(jobId, userId);
        Map<String, Object> body = new LinkedHashMap<>();
        // **必须把 triggered 一起返回**：false 表示"该响没响"（流程已结束、
        // token 已挪走），它不是失败，但调用方需要区分这两种情况
        body.put("jobId", jobId);
        body.put("triggered", Boolean.valueOf(fired));
        body.put("note", fired ? "已触发"
                : "该响没响：流程已结束或 token 已不在原节点上（不是失败，可忽略）");
        return Result.success(body);
    }

    @GetMapping
    @Operation(summary = "002_查 job（管理端/排障）")
    public Result<List<WfViews.JobView>> list(@RequestParam(required = false) String processInstanceId,
                                              @RequestParam(required = false) String elementId,
                                              @RequestParam(required = false) String type,
                                              @RequestParam(required = false) Integer pageNum,
                                              @RequestParam(required = false) Integer pageSize) {
        WfJobQuery query = new WfJobQuery()
                .setProcessInstanceId(processInstanceId)
                .setElementId(elementId)
                .setPageNum(pageNum == null ? 1 : pageNum)
                .setPageSize(pageSize == null ? 50 : pageSize);
        if (type != null && !type.trim().isEmpty()) {
            query.setType(com.zifang.z.wf.core.model.WfJobType
                    .valueOf(type.trim().toUpperCase()));
        }
        return Result.success(viewMapper.toJobViews(jobService.listJobs(query)));
    }

    @GetMapping("/count")
    @Operation(summary = "003_某类 job 还剩多少条")
    public Result<Long> count(@RequestParam(required = false) String processInstanceId,
                              @RequestParam(required = false) String type) {
        WfJobQuery query = new WfJobQuery().setProcessInstanceId(processInstanceId)
                .setPageNum(1).setPageSize(1);
        if (type != null && !type.trim().isEmpty()) {
            query.setType(com.zifang.z.wf.core.model.WfJobType
                    .valueOf(type.trim().toUpperCase()));
        }
        return Result.success(Long.valueOf(jobService.countJobs(query)));
    }

    @GetMapping("/exhausted")
    @Operation(summary = "004_重试耗尽的 job（失败清单）")
    public Result<List<WfViews.JobView>> exhausted(@RequestParam(required = false) Integer pageNum,
                                                   @RequestParam(required = false) Integer pageSize) {
        return Result.success(viewMapper.toJobViews(
                jobService.findExhaustedJobs(pageNum == null ? 1 : pageNum,
                        pageSize == null ? 50 : pageSize)));
    }
}
