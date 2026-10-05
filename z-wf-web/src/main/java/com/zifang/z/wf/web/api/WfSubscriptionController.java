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
import com.zifang.z.wf.core.model.WfJobType;
import com.zifang.z.wf.core.persistence.WfSubscriptionQuery;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfSubscriptionService;
import com.zifang.z.wf.core.view.WfSubscriptionView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 订阅查询 Controller —— "现在有哪些流程在等什么"。
 *
 * <p>基址 {@code /api/wf/subscriptions}，与 z-camuda 的 {@code EventSubscription} 对齐。
 *
 * <p><b>它为什么单独一个 Controller 而不并进 {@code WfTaskController}</b>：
 * 看起来都是"查待办"，但两者问的问题相反。待办回答"我要做什么"，
 * 订阅回答"它为什么不动"。混在一起的后果是排查时先看到一堆待办、
 * 以为单子在正常流转，而真正卡住的那条压根不在待办列表里 ——
 * 它在等一条消息，而消息投递方在别的系统里。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/subscriptions")
@Tag(name = "010_订阅查询")
public class WfSubscriptionController {

    @Resource
    private WfSubscriptionService subscriptionService;

    @GetMapping
    @Operation(summary = "001_查订阅：流程现在在等消息 / 等信号 / 等超时 / 等外部 worker")
    public Result<Map<String, Object>> list(@RequestParam(required = false) String processInstanceId,
                                            @RequestParam(required = false) String definitionKey,
                                            @RequestParam(required = false) String type,
                                            @RequestParam(required = false) String eventName,
                                            @RequestParam(required = false) String activityId,
                                            @RequestParam(required = false) Long waitingLongerThanMillis,
                                            @RequestParam(required = false) Boolean locked,
                                            @RequestParam(required = false) Integer pageNum,
                                            @RequestParam(required = false) Integer pageSize) {
        WfSubscriptionQuery query = new WfSubscriptionQuery()
                .setProcessInstanceId(processInstanceId)
                .setDefinitionKey(definitionKey)
                .setEventName(eventName)
                .setActivityId(activityId)
                .setWaitingLongerThanMillis(waitingLongerThanMillis)
                .setLocked(locked);
        for (WfJobType each : parseTypes(type)) {
            query.addType(each);
        }
        if (pageNum != null) {
            query.setPageNum(pageNum);
        }
        if (pageSize != null) {
            query.setPageSize(pageSize);
        }
        return Result.success(page(subscriptionService.listSubscriptions(query),
                subscriptionService.countSubscriptions(query)));
    }

    @GetMapping("/count")
    @Operation(summary = "002_只取总数：接监控打点用，比拉全量列表便宜得多")
    public Result<Map<String, Object>> count(@RequestParam(required = false) String processInstanceId,
                                             @RequestParam(required = false) String definitionKey,
                                             @RequestParam(required = false) String type) {
        WfSubscriptionQuery query = new WfSubscriptionQuery()
                .setProcessInstanceId(processInstanceId)
                .setDefinitionKey(definitionKey);
        for (WfJobType each : parseTypes(type)) {
            query.addType(each);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("count", subscriptionService.countSubscriptions(query));
        return Result.success(payload);
    }

    /**
     * 把 {@code type=MESSAGE,SIGNAL} 解析成 job 类型集合。
     *
     * <p>解析不了就<b>报错并列出合法值</b>，而不是当没传：静默忽略一个拼错的类型
     * 会让调用方拿到一份"看起来筛过了、其实没筛"的结果，
     * 而他正要靠这个结果判断"没有等待中的消息订阅"。
     */
    private List<WfJobType> parseTypes(String raw) {
        List<WfJobType> types = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return types;
        }
        List<String> names = new ArrayList<>();
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
                throw new WfEngineException("未知的订阅类型 [" + name + "]。合法值: " + names
                        + "（类型名区分不敏感）");
            }
        }
        return types;
    }

    private Map<String, Object> page(List<WfSubscriptionView> records, int total) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("records", records);
        payload.put("total", total);
        return payload;
    }
}
