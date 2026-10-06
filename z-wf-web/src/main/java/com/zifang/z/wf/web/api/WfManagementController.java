package com.zifang.z.wf.web.api;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.service.WfManagementService;
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
}