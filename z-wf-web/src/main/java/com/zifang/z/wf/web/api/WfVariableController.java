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
import com.zifang.z.wf.core.persistence.WfVariableInstanceQuery;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfVariableQueryService;
import com.zifang.z.wf.core.view.WfVariableInstanceView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 变量实例查询 Controller —— 「某个变量挂在哪一级作用域上、值是多少」。
 *
 * <p>基址 {@code /api/wf/variable-instances}。
 *
 * <p><b>它补的是哪一块</b>：{@code /api/wf/process/variables} 只能按名读写
 * <b>流程级</b>的变量，{@code /api/wf/process/branch-variables} 只能按 taskId
 * 读写<b>分支级</b>的那一层。两者都答不了排障时真正在问的问题 ——
 * 「这单上一共有哪些变量、各自在哪一级、是不是还生效」。
 *
 * <p><b>为什么这里是全仓唯一露出 executionId 的读端点</b>：
 * 写路径（分支变量）刻意只收 taskId，仓里有测试钉着
 * 「任务响应里不得出现 executionId」—— 那是<b>写</b>路径的约束，
 * 因为把执行树暴露成可写标识，就等于让调用方按内部结构改状态。
 * 本端点是<b>纯读</b>的诊断视图，而"变量挂在哪一级"这个问题
 * 没有作用域的键就答不出来（执行级那一层的 id 本身就是
 * {@code execution:<executionId>/<name>}），藏掉它只会让答案缺一头。
 *
 * <p><b>与 Camunda 的差异</b>：本端点没有 historic 变体，
 * 查的是"此刻还挂着的"；{@code loopCounter} / {@code loopAssignee}
 * 这类引擎内部变量默认不列（{@code includeEngineInternal=true} 可打开）。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/variable-instances")
@Tag(name = "012_变量实例查询")
public class WfVariableController {

    @Resource
    private WfVariableQueryService variableQueryService;

    @GetMapping
    @Operation(summary = "001_查变量实例：每个变量挂在哪一级作用域上、值是多少")
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String executionId,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String nameLike,
            @RequestParam(required = false) String valueEquals,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Boolean openTasksOnly,
            @RequestParam(required = false) Boolean includeEngineInternal,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        WfVariableInstanceQuery query = buildQuery(processInstanceId, executionId, taskId,
                name, nameLike, valueEquals, scope, openTasksOnly, includeEngineInternal);
        if (pageNum != null) {
            query.setPageNum(pageNum);
        }
        if (pageSize != null) {
            query.setPageSize(pageSize);
        }
        return Result.success(page(variableQueryService.listVariables(query),
                variableQueryService.countVariables(query)));
    }

    @GetMapping("/count")
    @Operation(summary = "002_只取总数：接监控打点用，比拉全量列表便宜得多")
    public Result<Map<String, Object>> count(@RequestParam(required = false) String processInstanceId,
                                             @RequestParam(required = false) String executionId,
                                             @RequestParam(required = false) String taskId,
                                             @RequestParam(required = false) String name,
                                             @RequestParam(required = false) String nameLike,
                                             @RequestParam(required = false) String valueEquals,
                                             @RequestParam(required = false) String scope,
                                             @RequestParam(required = false) Boolean openTasksOnly,
                                             @RequestParam(required = false) Boolean includeEngineInternal) {
        WfVariableInstanceQuery query = buildQuery(processInstanceId, executionId, taskId,
                name, nameLike, valueEquals, scope, openTasksOnly, includeEngineInternal);
        Map<String, Object> payload = new HashMap<>();
        payload.put("count", variableQueryService.countVariables(query));
        return Result.success(payload);
    }

    /**
     * <b>count 与 list 走同一套入参解析</b>：写成两套的话，两边认的合法值一旦漂移，
     * "总数"和"列表条数"就会对不上，而调用方只会以为自己算错了。
     *
     * <p><b>两个布尔默认值只在参数真的传了时才写回去</b>：
     * {@code @RequestParam(required = false)} 缺省时给的是 {@code null}，
     * 无条件 {@code setOpenTasksOnly(null)} 会把查询对象里那份
     * {@code TRUE}/{@code FALSE} 的默认值直接覆盖成 null ——
     * 端点看上去支持这个开关，实际上「不传」与「传 false」走的是同一条路，
     * 而文档里写的默认值是假的。
     */
    private WfVariableInstanceQuery buildQuery(String processInstanceId, String executionId,
                                               String taskId, String name, String nameLike,
                                               String valueEquals, String scope,
                                               Boolean openTasksOnly, Boolean includeEngineInternal) {
        WfVariableInstanceQuery query = new WfVariableInstanceQuery()
                .setProcessInstanceId(processInstanceId)
                .setExecutionId(executionId)
                .setTaskId(taskId)
                .setName(name)
                .setNameLike(nameLike)
                .setValueEquals(valueEquals);
        if (openTasksOnly != null) {
            query.setOpenTasksOnly(openTasksOnly);
        }
        if (includeEngineInternal != null) {
            query.setIncludeEngineInternal(includeEngineInternal);
        }
        for (String each : parseScopes(scope)) {
            query.addScope(each);
        }
        return query;
    }

    /**
     * 把 {@code scope=process,task} 解析成作用域集合。
     *
     * <p>解析不了就<b>报错并列出合法值</b>，而不是当没传 ——
     * 静默忽略一个拼错的作用域会让人拿到一份"少了 execution 那层"的清单，
     * 而那份清单看起来还挺正常，正是最容易读错结论的形态。
     */
    private List<String> parseScopes(String raw) {
        List<String> scopes = new ArrayList<String>();
        if (raw == null || raw.trim().isEmpty()) {
            return scopes;
        }
        List<String> legal = Arrays.asList(WfVariableInstanceView.SCOPE_PROCESS,
                WfVariableInstanceView.SCOPE_EXECUTION, WfVariableInstanceView.SCOPE_TASK);
        for (String part : Arrays.asList(raw.split(","))) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            String normalized = name.toLowerCase();
            if (!legal.contains(normalized)) {
                throw new WfEngineException("未知的变量作用域 [" + name + "]。合法值: " + legal
                        + "（可用英文逗号分隔多个，作用域名区分不敏感）");
            }
            scopes.add(normalized);
        }
        return scopes;
    }

    private Map<String, Object> page(List<WfVariableInstanceView> records, int total) {
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("records", records);
        payload.put("total", total);
        return payload;
    }
}
