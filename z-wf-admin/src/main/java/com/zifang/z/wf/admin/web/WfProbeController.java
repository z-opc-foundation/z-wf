package com.zifang.z.wf.admin.web;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.service.WfRepositoryService;

/**
 * 探针专用健康端点（免鉴权）。
 *
 * <p><b>为什么需要单独一个端点</b>：k3s 的 httpGet 探针<b>带不了自定义请求头</b>
 * （{@code readinessProbe.httpGet.httpHeaders} 只能写字面量，不能引用 Secret），
 * ⇒ 探针无法携带 Authorization。而 2026-10-22 起 {@code /api/wf/**} 全部要鉴权
 * （TASK-20261022-024），若让探针继续打 {@code /api/wf/health}，Pod 会永远 NotReady。
 *
 * <p><b>为什么不能直接豁免 {@code /api/wf/health}</b>：那条路径在本卡的验收口径里
 * 是 17 条受测读路径之一（匿名 GET 必须不可读）。豁免它 = 验收指标 ① 直接不成立。
 *
 * <p><b>这个端点暴露什么</b>：只有 {@code status} 与 {@code engine} 两个字段，
 * <b>没有</b> 流程定义数、没有实例/任务列表 —— 暴露的是「进程与存储活不活」，
 * 不是业务数据。库不可达时 status 变 DOWN（真查一次库，不是只报进程活着）。
 *
 * <p>该路径在 {@code z.wf.auth.exclude-paths} 里显式列出。
 *
 * @author zifang
 */
@RestController
public class WfProbeController {

    @Resource
    private WfRepositoryService repositoryService;

    /**
     * 探针端点。返 org 的统一信封（{@code Result}），与其它接口同形。
     */
    @GetMapping("/api/wf/health/probe")
    public Result<Map<String, Object>> probe() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("engine", "z-wf");
        try {
            // 真查一次库：只报「进程活着」而不验证存储可用的健康检查，等于没检查
            // （无 DataSource 时 WfAutoConfiguration 会静默回落内存实现 ⇒ 数据重启即丢）
            repositoryService.getAllDefinitions();
        } catch (Exception e) {
            body.put("status", "DOWN");
        }
        return Result.success(body);
    }
}
