package com.zifang.z.wf.web.api;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.model.WfFilter;
import com.zifang.z.wf.core.model.WfFilterType;
import com.zifang.z.wf.core.persistence.WfFilterQuery;
import com.zifang.z.wf.core.service.WfEngineException;
import com.zifang.z.wf.core.service.WfFilterService;
import com.zifang.z.wf.core.view.WfFilterResult;
import com.zifang.z.wf.web.dto.WfRequests;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 保存筛选器 Controller —— 把一组查询条件存起来，反复用、大家共用。
 *
 * <p>基址 {@code /api/wf/filters}。
 *
 * <p><b>它补的是哪一块</b>：待办、实例、故障三个列表的筛选条件
 * 此前每次都要现输，而输错一个字段的代价是"少掉的那部分永远没人来报"。
 * 存下来之后条件只有一份，且<b>存的时候就验过能用</b>（见 {@code WfFilterService}）。
 *
 * <p><b>{@code /results} 端点按筛选器自己的类型返回</b>，
 * 调用方不需要也不应该再传一个 resourceType：
 * 传了就会出现"筛选器是 task 的、却按 incident 去解释结果"这种错配，
 * 而它的症状是读到一堆 null 字段 —— 不报错，只是没人发现。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/filters")
@Tag(name = "013_保存筛选器")
public class WfFilterController {

    @Resource
    private WfFilterService filterService;

    @GetMapping
    @Operation(summary = "001_列筛选器（可按名字 / 类型 / 创建人筛）")
    public Result<Map<String, Object>> list(@RequestParam(required = false) String name,
                                            @RequestParam(required = false) String nameLike,
                                            @RequestParam(required = false) String resourceType,
                                            @RequestParam(required = false) String owner,
                                            @RequestParam(required = false) Integer pageNum,
                                            @RequestParam(required = false) Integer pageSize) {
        WfFilterQuery query = buildQuery(name, nameLike, resourceType, owner);
        if (pageNum != null) {
            query.setPageNum(pageNum);
        }
        if (pageSize != null) {
            query.setPageSize(pageSize);
        }
        return Result.success(page(filterService.listFilters(query),
                filterService.countFilters(query)));
    }

    @GetMapping("/count")
    @Operation(summary = "002_只取总数")
    public Result<Map<String, Object>> count(@RequestParam(required = false) String name,
                                             @RequestParam(required = false) String nameLike,
                                             @RequestParam(required = false) String resourceType,
                                             @RequestParam(required = false) String owner) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("count", filterService.countFilters(
                buildQuery(name, nameLike, resourceType, owner)));
        return Result.success(payload);
    }

    @GetMapping("/{filterId}")
    @Operation(summary = "003_取一张筛选器")
    public Result<Map<String, Object>> get(@PathVariable String filterId) {
        return Result.success(toView(filterService.getFilter(filterId)));
    }

    @PostMapping
    @Operation(summary = "004_新建筛选器（条件当场验能不能用，存不进去的直接报错）")
    public Result<Map<String, Object>> create(@RequestBody WfRequests.FilterOperation request) {
        return Result.success(toView(filterService.createFilter(
                requireName(request), WfFilterType.parse(requireType(request)),
                request == null ? null : request.getOwner(),
                toStringMap(request))));
    }

    @PutMapping("/{filterId}")
    @Operation(summary = "005_改筛选器（类型不可改：请求里的 resourceType 会被忽略，保持库里那份）")
    public Result<Map<String, Object>> update(@PathVariable String filterId,
                                              @RequestBody WfRequests.FilterOperation request) {
        WfFilter existing = filterService.getFilter(filterId);
        WfFilter target = new WfFilter();
        target.setId(filterId);
        // 版本号必须带过来：服务层拿它跟库里那份比对，漏了的话**第二次改必然冲突** ——
        // 而症状是「第一次能改、之后全报乐观锁冲突」，看起来像并发问题，
        // 实际是这个 PUT 端点压根没把并发信息传下去
        target.setRevision(existing.getRevision());
        target.setName(requireName(request));
        // 类型从库里那份取，而不是从请求体取：请求体里可以不带这一项，
        // 而带了也不能改。让它走"请求与库里的不一致就报错"那条路，
        // 调用方才会知道自己改类型这件事是不被允许的
        target.setResourceType(existing.getResourceType());
        target.setOwner(request == null ? existing.getOwner() : request.getOwner());
        target.setProperties(toStringMap(request));
        return Result.success(toView(filterService.updateFilter(target)));
    }

    @DeleteMapping("/{filterId}")
    @Operation(summary = "006_删筛选器（不存在就报错，不静默返回成功）")
    public Result<Void> delete(@PathVariable String filterId) {
        filterService.deleteFilter(filterId);
        return Result.success();
    }

    @GetMapping("/{filterId}/results")
    @Operation(summary = "007_跑这张筛选器：按它自己的类型返回任务 / 实例 / 故障")
    public Result<WfFilterResult> results(@PathVariable String filterId,
                                          @RequestParam(required = false) Integer pageNum,
                                          @RequestParam(required = false) Integer pageSize) {
        return Result.success(filterService.run(filterId,
                pageNum == null ? 1 : pageNum, pageSize == null ? 20 : pageSize));
    }

    /**
     * <b>count 与 list 走同一套入参解析</b>：写成两套的话，
     * 两边认的合法值一旦漂移，"总数"和"列表条数"就会对不上，
     * 而调用方只会以为自己算错了。
     */
    private WfFilterQuery buildQuery(String name, String nameLike, String resourceType, String owner) {
        WfFilterQuery query = new WfFilterQuery()
                .setName(name)
                .setNameLike(nameLike)
                .setOwner(owner);
        // 同样只在真的传了时才写回去：不传时保持 null（= 不筛），
        // 无条件 setResourceType(null) 倒也没错，但把"没传"与"传空"混成
        // 同一种处理之后，想给"显式清掉某个条件"留位置就没有了
        if (resourceType != null && !resourceType.trim().isEmpty()) {
            query.setResourceType(WfFilterType.parse(resourceType));
        }
        return query;
    }

    private String requireName(WfRequests.FilterOperation request) {
        if (request == null || request.getName() == null || request.getName().trim().isEmpty()) {
            throw new WfEngineException("筛选器名称不能为空 —— "
                    + "它是要出现在列表里给人挑的那个名字");
        }
        return request.getName();
    }

    private String requireType(WfRequests.FilterOperation request) {
        if (request == null || request.getResourceType() == null) {
            throw new WfEngineException("筛选器类型不能为空。合法值: " + WfFilterType.allCodes());
        }
        return request.getResourceType();
    }

    /**
     * DTO 里的条件值统一收敛成字符串。
     *
     * <p>刻意<b>不做静默的类型转换</b>：值是 {@code String} 就原样给，
     * 是别的类型就报错并说清该写什么。
     * 直接 {@code String.valueOf} 的话，{@code {"openOnly": 1}} 会变成
     * {@code "1"}，而它既不是 true 也不是 false ——
     * 在服务层那会被拒绝，可这里如果顺手转了，错误就从"存的时候就报错"
     * 推迟成"存得进去、跑的时候才炸"。
     */
    private Map<String, String> toStringMap(WfRequests.FilterOperation request) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (request == null || request.getProperties() == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : request.getProperties().entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                result.put(entry.getKey(), null);
            } else if (value instanceof String) {
                result.put(entry.getKey(), (String) value);
            } else if (value instanceof Boolean || value instanceof Number) {
                throw new WfEngineException("条件 [" + entry.getKey() + "] 的值必须写成字符串，"
                        + "实际收到 " + value.getClass().getSimpleName() + "。JSON 里的 true / 500 "
                        + "天生长成布尔与数字，但筛选器的值是「按字符串严格解析」的"
                        + "（openOnly 只认 \"true\" / \"false\"），"
                        + "自动转一下会让「写错了」变成「存得进去、跑的时候才炸」。"
                        + "请显式写成字符串: \"" + value + "\"");
            } else {
                throw new WfEngineException("条件 [" + entry.getKey() + "] 的值必须是字符串，"
                        + "实际收到 " + value.getClass().getName()
                        + "。筛选器的条件是「名字 → 字符串」，复杂结构请换一个条件名");
            }
        }
        return result;
    }

    private Map<String, Object> page(java.util.List<WfFilter> records, int total) {
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("records", toViews(records));
        payload.put("total", total);
        return payload;
    }

    /**
     * 筛选器 → 响应结构。
     *
     * <p><b>刻意不用实体直接序列化</b>：{@code WfFilter.resourceType} 是枚举，
     * 直接吐出去是 {@code TASK}，而 {@code /results} 里的同一个字段是
     * {@code task}。同一个字段名两套词汇，调用方就得在两个地方记两种拼法 ——
     * 而拼错的那一种不会报错，只会让筛选器的类型比不上。
     * 这里统一成短名。
     */
    private java.util.List<Map<String, Object>> toViews(java.util.List<WfFilter> filters) {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<Map<String, Object>>();
        if (filters == null) {
            return list;
        }
        for (WfFilter filter : filters) {
            list.add(toView(filter));
        }
        return list;
    }

    private Map<String, Object> toView(WfFilter filter) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", filter.getId());
        view.put("name", filter.getName());
        view.put("resourceType", filter.getResourceType() == null ? null
                : filter.getResourceType().getCode());
        view.put("owner", filter.getOwner());
        view.put("properties", filter.getProperties());
        view.put("createTime", filter.getCreateTime());
        view.put("updateTime", filter.getUpdateTime());
        view.put("revision", filter.getRevision());
        return view;
    }
}
