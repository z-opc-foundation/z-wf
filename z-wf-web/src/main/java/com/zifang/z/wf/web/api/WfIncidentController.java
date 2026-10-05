package com.zifang.z.wf.web.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.persistence.WfIncidentQuery;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfIncidentService;
import com.zifang.z.wf.core.view.WfIncidentView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 运行期故障查询 Controller —— "哪些事情没干成，而且正在为此付出代价"。
 *
 * <p>基址 {@code /api/wf/incidents}。
 *
 * <p><b>它与 {@code /api/wf/subscriptions} 是一对，缺一不可</b>：
 * 订阅回答"在等什么"，本端点回答"哪件事已经没干成"。
 * 只有订阅时，"单子不动了"有两种可能 —— 在耐心等，还是已经炸了没人管 ——
 * 而这两者的处置完全不同（前者可以等，后者必须人去看）。
 * 只有故障时同样不够：大量"不动"其实是正常的等待，逐条去翻 job 表既慢又吵。
 *
 * <p><b>与 Camunda 的 Incident 的差异</b>（有意为之，不是没做完）：
 * 本端点从 {@code ZWF_JOB} 派生，不建独立的 incident 表，也没有
 * acknowledge / resolve 生命周期。理由见 {@link WfIncidentView} ——
 * 一件事两处真源在排障时是最不能容忍的形态。
 * 代价是只覆盖<b>当前</b>故障，job 一旦执行成功就被删掉，事后复盘查不到。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/incidents")
@Tag(name = "011_运行期故障查询")
public class WfIncidentController {

    @Resource
    private WfIncidentService incidentService;

    @GetMapping
    @Operation(summary = "001_查故障：哪些 job 失败了、重试还剩几次、报的是什么错")
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String definitionKey,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String activityId,
            @RequestParam(required = false) Boolean retriesExhausted,
            @RequestParam(required = false) Long failedBefore,
            @RequestParam(required = false) String errorMessageContains,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        WfIncidentQuery query = new WfIncidentQuery()
                .setProcessInstanceId(processInstanceId)
                .setDefinitionKey(definitionKey)
                .setActivityId(activityId)
                .setRetriesExhausted(retriesExhausted)
                .setErrorMessageContains(errorMessageContains);
        for (WfJobType each : parseTypes(type)) {
            query.addType(each);
        }
        if (failedBefore != null) {
            // 毫秒时间戳：Spring 能把 ISO 字符串也转过来，但接口文档里给一个
            // 各家都认的写法，比让调用方猜这个项目吃哪种格式更省事
            query.setFailedBefore(new Date(failedBefore));
        }
        if (pageNum != null) {
            query.setPageNum(pageNum);
        }
        if (pageSize != null) {
            query.setPageSize(pageSize);
        }
        return Result.success(page(incidentService.listIncidents(query),
                incidentService.countIncidents(query)));
    }

    @GetMapping("/count")
    @Operation(summary = "002_只取总数：接监控打点用，比拉全量列表便宜得多")
    public Result<Map<String, Object>> count(@RequestParam(required = false) String processInstanceId,
                                             @RequestParam(required = false) String definitionKey,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) Boolean retriesExhausted) {
        WfIncidentQuery query = new WfIncidentQuery()
                .setProcessInstanceId(processInstanceId)
                .setDefinitionKey(definitionKey)
                .setRetriesExhausted(retriesExhausted);
        for (WfJobType each : parseTypes(type)) {
            query.addType(each);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("count", incidentService.countIncidents(query));
        return Result.success(payload);
    }

    /**
     * 把 {@code type=TIMER,EXTERNAL} 解析成 job 类型集合。
     *
     * <p>解析不了就<b>报错并列出合法值</b>，而不是当没传 ——
     * 在故障查询上更不能这样：静默忽略一个拼错的类型会让人拿到
     * "筛过了、没有故障"的结论，而故障恰恰是最不能误判成"没有"的东西。
     */
    private List<WfJobType> parseTypes(String raw) {
        List<WfJobType> types = new ArrayList<WfJobType>();
        if (raw == null || raw.trim().isEmpty()) {
            return types;
        }
        List<String> names = new ArrayList<String>();
        for (WfJobType each : WfJobType.values()) {
            names.add(each.name());
        }
        for (String part : Arrays.asList(raw.split(","))) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                types.add(WfJobType.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new WfEngineException("未知的故障类型 [" + name + "]。合法值: " + names
                        + "（类型名区分不敏感）");
            }
        }
        return types;
    }

    private Map<String, Object> page(List<WfIncidentView> records, int total) {
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("records", records);
        payload.put("total", total);
        return payload;
    }
}