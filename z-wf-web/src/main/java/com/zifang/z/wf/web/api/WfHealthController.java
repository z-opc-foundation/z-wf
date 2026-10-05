package com.zifang.z.wf.web.api;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.service.WfRepositoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 健康检查 Controller。
 *
 * <p>基址 {@code /api/wf}，与 z-camuda 的 {@code CamudaBaseHealthController} 同址，
 * 便于把 z-camuda 换成 z-wf 时容器编排的 healthcheck 不用改
 * （z-camuda 的 Dockerfile 打的就是 {@code /api/wf/health}）。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf")
@Tag(name = "005_健康检查")
public class WfHealthController {

    @Resource
    private WfRepositoryService repositoryService;

    @Resource
    private WfPersistence persistence;

    @GetMapping("/health")
    @Operation(summary = "001_健康检查")
    public Result<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("engine", "z-wf");
        body.put("persistence", persistence.getClass().getSimpleName());
        try {
            // 真查一次库：只报"进程活着"而不验证存储可用的健康检查，等于没检查
            int definitionCount = repositoryService.getAllDefinitions().size();
            body.put("definitionCount", definitionCount);
        } catch (Exception e) {
            body.put("status", "DOWN");
            body.put("error", e.getMessage());
        }
        return Result.success(body);
    }
}
