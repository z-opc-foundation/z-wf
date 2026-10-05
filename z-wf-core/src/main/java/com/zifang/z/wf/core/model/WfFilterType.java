package com.zifang.z.wf.core.model;

import java.util.Arrays;
import java.util.List;

/**
 * 保存筛选器的适用资源类型。
 *
 * <p><b>为什么类型要显式存下来</b>：筛选器存的是一组"名字 → 值"的条件，
 * 而同一个名字在不同查询里含义不同（{@code status} 在任务查询里是
 * {@code CREATED/ASSIGNED/…}，在实例查询里是 {@code ACTIVE/COMPLETED/…}）。
 * 不记类型的话，一个按"待办"存的筛选器可以被拿去查故障，
 * 条件被逐个套到不相干的字段上 —— 不报错，只是查出来的东西没人认得。
 *
 * <p>与 Camunda 的差异：Camunda 的 filter 不存类型，
 * 而是由调用方选工厂（{@code taskService.createTaskQuery().filterId(id)}）来决定，
 * 类型错配的代价是那条条件被静默忽略。**本仓选择存下来并校验**：
 * 一次类型错配会直接让整张筛选器用不了，而"用不了"是看得见的，
 * "查出来一份没人认得的清单"是看不见的。
 *
 * @author zifang
 */
public enum WfFilterType {

    /** 任务（待办 / 已办）。 */
    TASK("task"),

    /** 流程实例（在途 / 已结束）。 */
    PROCESS_INSTANCE("processInstance"),

    /** 运行期故障。 */
    INCIDENT("incident");

    private final String code;

    WfFilterType(String code) {
        this.code = code;
    }

    /** REST 层与配置里用的短名。 */
    public String getCode() {
        return code;
    }

    public static List<String> allCodes() {
        return Arrays.asList(TASK.code, PROCESS_INSTANCE.code, INCIDENT.code);
    }

    /**
     * 按短名或枚举名解析，**两者都认**且**大小写不敏感**。
     *
     * <p>解析不了就抛，由调用方补上"合法值是什么"。
     * 本方法刻意不返回 {@code null}：那会把"类型拼错了"变成
     * "筛选器没有类型"，而后者在查询时才炸，位置离错误现场很远。
     */
    public static WfFilterType parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("筛选器类型不能为空。合法值: " + allCodes());
        }
        String text = raw.trim();
        for (WfFilterType each : values()) {
            if (each.code.equalsIgnoreCase(text) || each.name().equalsIgnoreCase(text)) {
                return each;
            }
        }
        throw new IllegalArgumentException("未知的筛选器类型 [" + raw + "]。合法值: " + allCodes()
                + "（短名与枚举名都认，大小写不敏感）");
    }
}
