package com.zifang.z.wf.web.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.zifang.util.core.meta.Result;
import com.zifang.z.wf.core.definition.dmn.WfDmnDecision;
import com.zifang.z.wf.core.definition.dmn.WfDmnHitPolicy;
import com.zifang.z.wf.core.engine.dmn.WfDmnDecisionResult;
import com.zifang.z.wf.core.service.WfDecisionService;
import com.zifang.z.wf.core.service.WfEngineException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * DMN 决策表 Controller —— 部署决策表、按 key 求值。
 *
 * <p>基址 {@code /api/wf/decisions}。
 *
 * <p><b>求值端点刻意不接受「指定版本」以外的任何东西</b>：调用方给一张表 + 一组变量，
 * 拿到结论。多一个参数就多一种"这次到底算的是哪张表"的歧义，
 * 而在途流程引用的必须是<b>部署那一刻</b>的版本 ——
 * 那个版本由流程自己带，这里若允许随手指定，调用方就会在版本切换后静默算出另一个结论。
 * 需要固定版本的场景走 {@code /versions/{version}} 的读取端点，不走求值。
 *
 * <p><b>响应刻意不用实体直接序列化</b>（与 {@link WfFilterController} 同一条理由）：
 * {@link WfDmnHitPolicy} 直接吐出去是枚举名，而部署时人写的是
 * {@code hitPolicy="U"}。同一个字段两套词汇，调用方就得记两种拼法，
 * 而拼错的那一种不会报错，只会让这张表比不上。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/wf/decisions")
@Tag(name = "014_DMN 决策表")
public class WfDecisionController {

    @Resource
    private WfDecisionService decisionService;

    @PostMapping("/deploy")
    @Operation(summary = "001_部署 DMN XML（一个文件里的多个 decision 全部部署，返回各自的 key 与版本）")
    public Result<Map<String, Object>> deploy(@RequestBody DeployRequest request) {
        if (request == null || request.getDmnXml() == null || request.getDmnXml().trim().isEmpty()) {
            // 空内容会在解析器里报一句与调用方语境无关的错，这里先挡一次
            throw new WfEngineException("部署内容不能为空");
        }
        List<String> keys = decisionService.deployDecision(request.getDmnXml());
        List<Map<String, Object>> deployed = new ArrayList<Map<String, Object>>();
        for (String key : keys) {
            Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("key", key);
            one.put("version", decisionService.findDecisionByKey(key).getVersion());
            deployed.add(one);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("deployed", deployed);
        return Result.success(payload);
    }

    @GetMapping("/{key}")
    @Operation(summary = "002_取某个 key 的最新版本")
    public Result<Map<String, Object>> latest(@PathVariable String key) {
        return Result.success(toView(require(key)));
    }

    @GetMapping("/{key}/versions")
    @Operation(summary = "003_列某个 key 的所有版本（新版本在前）")
    public Result<Map<String, Object>> versions(@PathVariable String key) {
        List<WfDmnDecision> found = decisionService.findDecisionsByKey(key);
        if (found.isEmpty()) {
            throw notFound(key);
        }
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (WfDmnDecision decision : found) {
            views.add(toView(decision));
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("records", views);
        payload.put("total", views.size());
        return Result.success(payload);
    }

    @GetMapping("/{key}/versions/{version}")
    @Operation(summary = "004_取指定版本")
    public Result<Map<String, Object>> version(@PathVariable String key,
                                                @PathVariable int version) {
        WfDmnDecision decision = decisionService.findDecisionByKey(key, version);
        if (decision == null) {
            throw notFound(key, version);
        }
        return Result.success(toView(decision));
    }

    @PostMapping("/{key}/evaluate")
    @Operation(summary = "005_求值：给一组变量，拿到决策结论")
    public Result<Map<String, Object>> evaluate(@PathVariable String key,
                                                 @RequestBody EvaluateRequest request) {
        WfDmnDecisionResult result = decisionService.evaluateDecision(key,
                request == null ? null : request.getVariables());
        return Result.success(toResultView(result));
    }

    @DeleteMapping("/{key}/versions/{version}")
    @Operation(summary = "006_删掉指定版本（不存在就报错，不静默返回成功）")
    public Result<Void> delete(@PathVariable String key, @PathVariable int version) {
        if (!decisionService.deleteDecision(key, version)) {
            throw notFound(key, version);
        }
        return Result.success();
    }

    // ==================== 转换 ====================

    private WfDmnDecision require(String key) {
        WfDmnDecision decision = decisionService.findDecisionByKey(key);
        if (decision == null) {
            throw notFound(key);
        }
        return decision;
    }

    private WfEngineException notFound(String key) {
        return new WfEngineException("决策 [" + key + "] 不存在 —— "
                + "先调 /api/wf/decisions/deploy 部署它");
    }

    private WfEngineException notFound(String key, int version) {
        return new WfEngineException("决策 [" + key + "] 的版本 " + version + " 不存在 —— "
                + "先调 /api/wf/decisions/{key}/versions 看有哪些版本");
    }

    /**
     * 决策定义 → 响应结构。
     *
     * <p>{@code hitPolicy} 给的是<b>枚举名</b>（UNIQUE / ANY / ...），
     * 与 {@code /deploy} 请求里人写的那种符号（{@code U} / {@code A}）不是一回事：
     * 前者是响应里稳定的机器词汇，后者是 DMN 文件里的简写。
     * 两个都给出，调用方按场景挑，不必自己记映射。
     */
    private Map<String, Object> toView(WfDmnDecision decision) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("key", decision.getKey());
        view.put("name", decision.getName());
        view.put("version", decision.getVersion());
        view.put("deployTime", decision.getDeployTime());
        WfDmnDecision.WfDmnTable table = decision.getTable();
        if (table == null) {
            return view;
        }
        Map<String, Object> tableView = new LinkedHashMap<String, Object>();
        tableView.put("id", table.getId());
        tableView.put("hitPolicy", table.getHitPolicy() == null ? null
                : table.getHitPolicy().name());
        tableView.put("hitPolicySymbol", table.getHitPolicy() == null ? null
                : table.getHitPolicy().symbol());
        tableView.put("aggregator", table.getAggregator());
        tableView.put("inputExpressions", table.getInputExpressions());

        List<Map<String, Object>> outputs = new ArrayList<Map<String, Object>>();
        for (WfDmnDecision.WfDmnOutput output : table.getOutputs()) {
            Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("name", output.getName());
            one.put("typeRef", output.getTypeRef());
            one.put("outputValues", output.getOutputValues());
            outputs.add(one);
        }
        tableView.put("outputs", outputs);

        List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
        for (WfDmnDecision.WfDmnRule rule : table.getRules()) {
            Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("inputEntries", rule.getInputEntries());
            one.put("outputEntries", rule.getOutputEntries());
            rules.add(one);
        }
        tableView.put("rules", rules);

        view.put("table", tableView);
        return view;
    }

    /**
     * 求值结果 → 响应结构。
     *
     * <p><b>{@code matchedRuleCount} 与 {@code rows} 分开给</b>：
     * "一条都没命中"（输入不对）与"命中了但输出是空的"（输出项写漏了）
     * 在运维眼里长得一模一样，而两者的处置完全不同 ——
     * 前者去查输入，后者去查表。把计数单独给出来，排查的人第一眼就能分。
     */
    private Map<String, Object> toResultView(WfDmnDecisionResult result) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("decisionKey", result.getDecisionKey());
        view.put("hitPolicy", result.getHitPolicy() == null ? null : result.getHitPolicy().name());
        view.put("matchedRuleCount", result.getMatchedRuleCount());
        view.put("noMatch", result.isNoMatch());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (WfDmnDecisionResult.Row row : result.getRows()) {
            rows.add(row.getOutputs());
        }
        view.put("rows", rows);
        return view;
    }

    /** 部署请求：只收 XML，不收 key —— DMN 里的 {@code <decision id>} 就是 key。 */
    public static class DeployRequest {
        private String dmnXml;

        public String getDmnXml() {
            return dmnXml;
        }

        public void setDmnXml(String dmnXml) {
            this.dmnXml = dmnXml;
        }
    }

    /** 求值请求：变量表。没传就按空表求 —— 空表下多数规则会 fail-closed 成不命中。 */
    public static class EvaluateRequest {
        private Map<String, Object> variables;

        public Map<String, Object> getVariables() {
            return variables;
        }

        public void setVariables(Map<String, Object> variables) {
            this.variables = variables;
        }
    }
}