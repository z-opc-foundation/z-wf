package com.zifang.z.wf.web.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.persistence.WfExecutionQuery;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfExecutionQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 执行令牌查询 Controller —— 「哪条 token 停在哪」。
 *
 * <p>基址 {@code /api/wf/executions}，与 z-camuda 的 {@code Execution} 对齐。
 *
 * <p><b>与订阅查询是两个端点而不是一个的原因</b>：两者回答同一个问题的两半。
 * 订阅答「它在等一个事件（消息 / 信号 / 超时 / 外部 worker）」，
 * 令牌答「它停在哪一步」。只给一半时排障的人会得出错误结论 ——
 * 看到有订阅就说"在等消息"，而真相可能是它压根不在等，是某条分支走完_join_ 之后
 * 另一条早就结束了但没人合。两者都在时，那句话才是完整的。
 *
 * <p><b>为什么按节点查是有意义的</b>：既有的
 * {@code GET /api/wf/process/{id}/executions} 必须先知道流程实例 id，
 * 而"哪个单子卡在审批节点上"这句话里此刻还只有节点、没有单子。
 * 加上 {@code activityId} 这个筛子之后，那句话才问得出口。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/executions")
@Tag(name = "011_令牌查询")
public class WfExecutionController {

    @Resource
    private WfExecutionQueryService executionQueryService;

    @GetMapping
    @Operation(summary = "001_查令牌：按流程实例 / 节点 / 状态 / 变量查它停在哪")
    public Result<Map<String, Object>> list(@RequestParam(required = false) String processInstanceId,
                                            @RequestParam(required = false) String activityId,
                                            @RequestParam(required = false) String state,
                                            @RequestParam(required = false) String variableName,
                                            @RequestParam(required = false) String variableValue,
                                            @RequestParam(required = false) Boolean unfinishedOnly,
                                            @RequestParam(required = false) Integer pageNum,
                                            @RequestParam(required = false) Integer pageSize) {
        WfExecutionQuery query = new WfExecutionQuery()
                .setProcessInstanceId(processInstanceId)
                .setActivityId(activityId)
                .setVariableName(variableName)
                .setVariableValueEquals(variableValue);
        for (WfExecution.State each : parseStates(state)) {
            query.addState(each);
        }
        if (unfinishedOnly != null && unfinishedOnly) {
            query.onlyUnfinished();
        }
        if (pageNum != null) {
            query.setPageNum(pageNum);
        }
        if (pageSize != null) {
            query.setPageSize(pageSize);
        }
        return Result.success(page(executionQueryService.listExecutions(query),
                executionQueryService.countExecutions(query)));
    }

    @GetMapping("/count")
    @Operation(summary = "002_只取总数：接监控打点用，比拉全量列表便宜得多")
    public Result<Map<String, Object>> count(@RequestParam(required = false) String processInstanceId,
                                             @RequestParam(required = false) String activityId,
                                             @RequestParam(required = false) String state,
                                             @RequestParam(required = false) String variableName,
                                             @RequestParam(required = false) String variableValue) {
        WfExecutionQuery query = new WfExecutionQuery()
                .setProcessInstanceId(processInstanceId)
                .setActivityId(activityId)
                .setVariableName(variableName)
                .setVariableValueEquals(variableValue);
        for (WfExecution.State each : parseStates(state)) {
            query.addState(each);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("count", executionQueryService.countExecutions(query));
        return Result.success(payload);
    }

    /**
     * 把 {@code state=ACTIVE,WAITING} 解析成状态集合。
     *
     * <p>解析不了就<b>报错并列出合法值</b>，而不是当没传 ——
     * 与订阅查询的 {@code parseTypes} 同一条理由：静默忽略一个拼错的状态，
     * 调用方会拿到一份"看起来筛过了、其实没筛"的结果，
     * 而他正要靠它判断"没有活跃的令牌"。
     */
    private List<WfExecution.State> parseStates(String raw) {
        List<WfExecution.State> states = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return states;
        }
        List<String> names = new ArrayList<>();
        for (WfExecution.State each : WfExecution.State.values()) {
            names.add(each.name());
        }
        for (String part : Arrays.asList(raw.split(","))) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                states.add(WfExecution.State.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new WfEngineException("未知的令牌状态 [" + name + "]。合法值: " + names
                        + "（状态名区分不敏感）");
            }
        }
        return states;
    }

    private Map<String, Object> page(List<WfExecution> records, int total) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("records", records);
        payload.put("total", total);
        return payload;
    }
}
