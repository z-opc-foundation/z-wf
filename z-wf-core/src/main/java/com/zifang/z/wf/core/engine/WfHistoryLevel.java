package com.zifang.z.wf.core.engine;

/**
 * 历史级别 —— 「记多少历史」的开关（第 42 轮，对齐 Camunda 的 {@code history-level}）。
 *
 * <p>Camunda 的四档（文档 User Guide「Choosing a History Level」，7.x 与 CIB SEVEN 一致）：
 * <ul>
 *   <li>{@code NONE}：不记任何历史</li>
 *   <li>{@code ACTIVITY}：记流程实例与活动实例的起止</li>
 *   <li>{@code AUDIT}：{@code ACTIVITY} + 变量实例的创建/更新/删除</li>
 *   <li>{@code FULL}：{@code AUDIT} + 变量更新的<b>中间值</b>（Historic Details）</li>
 * </ul>
 * Camunda 的默认档是 {@code AUDIT}，<b>本实现默认是 {@link #DEFAULT}（full）</b> ——
 * 理由与"从 Camunda 迁配置会踩的坑"都写在 {@link #DEFAULT} 上。
 *
 * <p><b>本实现与 Camunda 的两处有意偏离，写在这里是因为"看起来对齐了"最容易骗人。</b>
 *
 * <ol>
 *   <li><b>「评论 / 留痕」落在 AUDIT，而 Camunda 把用户操作日志放在 FULL。</b>
 *       理由不是口味：z-wf 的 {@code ZWF_COMMENT} 不只是"用户点了什么"，
 *       引擎自己也往里写 {@code error} / {@code event} / {@code external} /
 *       {@code job} 四类留痕 —— 「这一步为什么被走了」全靠它。
 *       压到 FULL 意味着默认档下排障看不到任何线索，
 *       而默认档必须等于本引擎引入本特性之前的行为（否则一开默认配置就坏一堆）。</li>
 *   <li><b>「变量审计」落在 FULL，对应的是 Camunda 的 Historic Details 而不是
 *       {@code Variable Instance}。</b> 理由：z-wf 没有「变量当前值的历史」这一层 ——
 *       {@code WfProcessInstance.variables} 是<b>运行期</b>数据，实例清理后就没了 ——
 *       唯一的历史形态就是「每次变更一条」的审计明细，
 *       那正是 Camunda 放在 FULL 的东西。把它放进 AUDIT 会让本实现的
 *       AUDIT 比 Camunda 的 AUDIT <b>更重</b>（多记了全部中间值）。</li>
 * </ol>
 *
 * <p><b>刻意不受级别控制的两样东西</b>（它们不是历史，是运行期事实）：
 * <ul>
 *   <li><b>历史故障记录</b>（{@code ZWF_INCIDENT_HISTORY}，第 40 轮）：
 *       对应 Camunda 的 Historic Job Log，而 Camunda 的 JobLog <b>不</b>受 history level
 *       控制（否则关掉历史就等于关掉"它坏过"这件事）。</li>
 *   <li><b>当前故障</b>（{@link com.zifang.z.wf.core.service.WfIncidentService}）：
 *       从 job 现场推导，job 本身是运行期数据。</li>
 * </ul>
 * 判据都是同一条：**「某个 job/实例现在怎么样」是运行期的问题，
 * 关掉历史不该让排障失去答案。**
 *
 * <p><b>没有运行期改级别的接口</b>，与 Camunda 一致（文档原话：
 * "the history level is stored in the database and cannot be changed later"）。
 * 本实现给的理由更强一点：级别一旦中途改过，
 * "上周五那张单子里哪些记录可信"就没有答案了 ——
 * 那不是配置问题，是数据里少了一份说法的历史。
 *
 * @author zifang
 */
public enum WfHistoryLevel {

    /** 一条历史都不记。 */
    NONE(0),

    /** 只记「流程走到哪、每步多久」。 */
    ACTIVITY(1),

    /** 在 ACTIVITY 之上加「谁说了什么」的留痕。 */
    AUDIT(2),

    /** 在 AUDIT 之上加「变量每次变过什么」的明细。 */
    FULL(3);

    /**
     * 本实现的默认档 = {@link #FULL}，**比 Camunda 的默认（audit）高一档**。
     *
     * <p><b>不能为了"对齐 Camunda"把默认设成 {@code AUDIT}</b>：
     * 本引擎引入本特性之前<b>变量审计就是开着的</b>（{@code WfVariableService}
     * 每次变更留一条，8 条既有用例盯着它），而把默认设成低一档等于
     * 「<b>加上这个配置项本身</b>就丢了一个功能」——
     * 存量用户不改任何配置却发现变量变更查不到了，而且没有任何报错。
     * ⇒ 默认档必须是<b>与基线一致的那一档</b>，也就是本引擎这里恰好是 full。
     *
     * <p>因此<b>从 Camunda 迁配置过来要特别注意一条</b>：
     * Camunda 的 {@code audit} 会保留变量历史，本引擎的 {@code audit} <b>不</b>保留
     * —— 因为本引擎没有「变量当前值历史」这一层，唯一的历史形态就是
     * 「每次变更一条」的明细，那属于 full。照抄 {@code history=audit} 会静默丢掉变量明细。
     */
    public static final WfHistoryLevel DEFAULT = FULL;

    /** 数字大小即档位高低，与 Camunda 的常量值一致（便于对照）。 */
    private final int level;

    WfHistoryLevel(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public boolean isAtLeast(WfHistoryLevel other) {
        return this.level >= other.level;
    }

    /** 活动轨迹（{@code ZWF_ACTIVITY_INSTANCE}）：走的是 {@code WfContext} 的登记。 */
    public boolean recordsActivityHistory() {
        return isAtLeast(ACTIVITY);
    }

    /** 评论与留痕（{@code ZWF_COMMENT}）中的「人做了什么」。 */
    public boolean recordsComments() {
        return isAtLeast(AUDIT);
    }

    /** 变量审计明细（{@code WfVariableService} 每次变更留一条）。 */
    public boolean recordsVariableAudit() {
        return isAtLeast(FULL);
    }

    /**
     * 解析配置里的字符串。
     *
     * <p><b>认不出来就抛异常，不回落默认值。</b> 回落是最坏的一种失败：
     * 配 {@code full} 拼错成 {@code ful}，引擎默默按 {@code audit} 跑起来，
     * 业务方以为变量中间值都留着，直到合规审计那天才发现没有 ——
     * 而那时没有任何一条报错可查。
     *
     * @param raw 配置值，{@code null} / 空白按默认档 {@link #AUDIT}
     * @throws IllegalArgumentException 值不认识时
     */
    public static WfHistoryLevel parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT;
        }
        String wanted = raw.trim();
        for (WfHistoryLevel candidate : values()) {
            if (candidate.name().equalsIgnoreCase(wanted)) {
                return candidate;
            }
        }
        StringBuilder legal = new StringBuilder();
        for (WfHistoryLevel candidate : values()) {
            if (legal.length() > 0) {
                legal.append(" / ");
            }
            legal.append(candidate.name().toLowerCase());
        }
        throw new IllegalArgumentException("z.wf.history-level 认不出 \"" + raw
                + "\"，合法值只有：" + legal + "。不回落默认值 —— "
                + "悄悄按低一档跑，表现是「以为留着的东西其实没留」，"
                + "而那类问题要等到合规审计那天才会被发现");
    }

    /**
     * 中文说明，供管理端自省用。
     *
     * <p>刻意把「这一档会少记什么」写出来：运维看到 {@code activity} 时
     * 需要的正是"那我就查不到谁说了什么"这句，而不是一个孤零零的档位名。
     */
    public String describe() {
        switch (this) {
            case NONE:
                return "不记任何历史（排障只剩运行期数据：待办、job、故障）";
            case ACTIVITY:
                return "记活动轨迹（单子走到哪、每步多久）；不记评论留痕与变量审计";
            case AUDIT:
                return "记活动轨迹 + 评论留痕；**不记变量的每次变更**"
                        + "（注意：Camunda 的 audit 是保留变量历史的，"
                        + "本引擎的 audit 不是，两者不要照抄）";
            default:
                return "全记：活动轨迹 + 评论留痕 + 变量每次变更的明细";
        }
    }
}