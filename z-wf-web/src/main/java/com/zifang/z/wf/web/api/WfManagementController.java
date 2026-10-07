package com.zifang.z.wf.web.api;

import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.model.WfMetric;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfManagementService;
import com.zifang.z.wf.core.service.WfMetricRow;
import com.zifang.z.wf.core.service.WfMetricsQuery;
import com.zifang.z.wf.core.view.WfTableInfo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 引擎自省 Controller —— 「我连的是什么、底下有什么、有多少」。
 *
 * <p>对应 Camunda 的 {@code ManagementService}。基址 {@code /api/wf/management}。
 *
 * <p><b>权限提示</b>：这两个端点不返回任何凭据（连接串、账号、口令一律不给），
 * 但它们会把**行数**摊开给调用方 —— 在多租户场景里，"某个租户的单据占多少行"
 * 本身就是敏感信息。生产部署里应当按运维角色限制这个路径，
 * 不要因为"它不返回凭据"就当成无害接口。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/management")
@Tag(name = "009_引擎自省")
public class WfManagementController {

    @Resource
    private WfManagementService managementService;

    /**
     * 引擎属性：名字、版本、schema 版本、底下是表还是进程内集合。
     *
     * <p>刻意<b>不含连接信息</b>：自省接口常被监控无差别暴露，
     * 而"这是哪个引擎、什么版本"完全不需要凭据。
     */
    @GetMapping("/properties")
    @Operation(summary = "001_引擎属性（版本 / schema 版本 / 存储形态，不含任何凭据）")
    public Result<Map<String, Object>> properties() {
        return Result.success(managementService.getProperties());
    }

    /**
     * 存储清单：每项的名字、类型（表 / 集合）与行数。
     *
     * <p>{@code kind} 显式标出底下到底是表还是进程内集合 ——
     * 内存模式下看到 {@code ZWF_TASK} 而不标类型，人会理所当然地跑去数据库里找。
     */
    @GetMapping("/tables")
    @Operation(summary = "002_存储清单：名字 + 类型（表/集合）+ 行数")
    public Result<List<WfTableInfo>> tables() {
        return Result.success(managementService.getTables());
    }

    /**
     * 单项条数。
     *
     * <p>名字不认识返回 <b>400</b> 而不是 0：拼错一个表名得到 0，
     * 会把排障方向从「我拼错了」带偏到「谁把它清空了」。
     */
    @GetMapping("/tables/count")
    @Operation(summary = "003_某一项的行数（名字不存在时报 400，不返回 0）")
    public Result<Long> tableCount(@RequestParam String name) {
        return Result.success(managementService.getTableCount(name));
    }

    /**
     * 引擎指标（第 44 轮）：实例数 / 端到端时长 / 任务时长 / 按办理人的工作量。
     *
     * <p>对应 Camunda 的 {@code ManagementService#createMetricsQuery}。
     * 返回的列表<b>末尾固定有一行 {@code name="__ALL__"}</b>：
     * 直方图靠它给总数与最值，调用方不必自己把各桶相加 ——
     * 而"忘了相加"是这类接口最常见的用法错误。
     *
     * <p><b>指标名写错返回 400</b>，不静默当成 0：
     * 看板上的 0 会被当成业务结论，而"你拼错了指标名"不是业务结论。
     *
     * @param metric     {@code process-instances} / {@code process-instance-duration}
     *                   / {@code task-duration} / {@code task-users}，<b>大小写不敏感</b>
     * @param startDate  窗口下界（含），ISO-8601；不给 = 不限
     * @param endDate    窗口上界（含），ISO-8601；不给 = 不限
     * @param definitionKey 限定某个流程定义；不给 = 全部
     */
    @GetMapping("/metrics")
    @Operation(summary = "004_引擎指标（实例数 / 时长直方图 / 按办理人工作量；窗口按启动或创建时间）")
    public Result<List<WfMetricRow>> metrics(
            @RequestParam String metric,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String definitionKey) {
        WfMetricsQuery query = new WfMetricsQuery(parseMetric(metric))
                .setStartDate(parseDate(startDate, "startDate"))
                .setEndDate(parseDate(endDate, "endDate"))
                .setDefinitionKey(definitionKey);
        return Result.success(managementService.queryMetrics(query));
    }

    /**
     * 指标名解析。
     *
     * <p><b>大小写与连字符都要宽容</b>（{@code task-duration} / {@code TASK_DURATION} /
     * {@code taskDuration} 都认）：URL 上的查询参数是人手敲的，
     * 而为大小写报错等于让人去查一个根本不存在的问题。
     *
     * <p>但<b>不认识的指标名必须报错</b>，不能默认给一个：
     * 静默回落到某个指标上，看板上会挂着一份"看起来正常"却完全不是要的东西的数字。
     */
    private WfMetric parseMetric(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new WfEngineException("要算指标就得说清是哪一类：metric 必填。可选："
                    + java.util.Arrays.toString(WfMetric.values()));
        }
        String normalized = raw.trim().replace("-", "_").replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase();
        for (WfMetric candidate : WfMetric.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new WfEngineException("不认识的指标名 \"" + raw + "\"。可选："
                + java.util.Arrays.toString(WfMetric.values()));
    }

    /**
     * 时间参数解析。
     *
     * <p><b>固定用 {@code yyyy-MM-dd'T'HH:mm:ss}</b>，不用
     * {@code DateFormat.getDateTimeInstance()}：那个走的是<b>默认 Locale 的本地格式</b>
     * （中文环境里是 {@code 2026/10/1 下午3:04:05}），
     * 于是同一段代码在不同部署机器上接受不同的输入、拒绝另一些 ——
     * 而 URL 里的时间参数往往是别人从文档里抄的。
     * 本条的注释原先写的是「ISO-8601」而实现用的是本地格式，
     * 那是注释在说谎，已一并改掉。
     *
     * <p><b>不做多格式兜底</b>：兜底的代价是<b>同一个错字可能被解析成两个不同的日期</b>，
     * 而指标窗口错了之后没人会去核对。
     *
     * <p><b>参数名要写进报错</b>——三个可选日期参数长得一模一样，
     * 报一句「日期格式不对」等于让人自己猜是哪一页写错了。
     *
     * <p>{@code SimpleDateFormat} <b>不是线程安全的</b>，所以每次新建而不是提成字段：
     * controller 是单例，提成字段在并发下会偶发解析错乱 ——
     * 那类 bug 一天出现几次、且完全不可复现。
     */
    private Date parseDate(String raw, String paramName) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        // 非宽松：2026-13-45 这种要报错，不要被悄悄滚到下一年
        format.setLenient(false);
        try {
            return format.parse(raw.trim());
        } catch (ParseException ex) {
            throw new WfEngineException("参数 " + paramName + " 的时间格式认不出来：\"" + raw
                    + "\"。要 yyyy-MM-dd'T'HH:mm:ss，例如 2026-10-01T00:00:00。");
        }
    }
}