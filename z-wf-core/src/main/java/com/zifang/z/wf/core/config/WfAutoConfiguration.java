package com.zifang.z.wf.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.zifang.z.wf.core.engine.WfBehaviorRegistry;
import com.zifang.z.wf.core.engine.WfEngine;
import com.zifang.z.wf.core.engine.WfIdGenerator;
import com.zifang.z.wf.core.engine.expression.WfExpressionEvaluator;
import com.zifang.z.wf.core.hook.WfHookDispatcher;
import com.zifang.z.wf.core.hook.WfNotificationHook;
import com.zifang.z.wf.core.hook.WfProcessHook;
import com.zifang.z.wf.core.hook.WfTaskHook;
import com.zifang.z.wf.core.persistence.InMemoryWorkflowPersistence;
import com.zifang.z.wf.core.persistence.JdbcWorkflowPersistence;
import com.zifang.z.wf.core.persistence.WfPersistence;
import com.zifang.z.wf.core.service.WfBatchService;
import com.zifang.z.wf.core.service.WfDelegateRegistry;
import com.zifang.z.wf.core.service.WfDecisionService;
import com.zifang.z.wf.core.service.WfHistoryService;
import com.zifang.z.wf.core.service.WfHistoricIncidentService;
import com.zifang.z.wf.core.service.WfJobService;
import com.zifang.z.wf.core.service.WfExternalTaskService;
import com.zifang.z.wf.core.service.WfRepositoryService;
import com.zifang.z.wf.core.service.WfRuntimeService;
import com.zifang.z.wf.core.service.WfTaskService;
import com.zifang.z.wf.core.service.WfOverdueScanner;
import com.zifang.z.wf.core.service.WfIncidentService;
import com.zifang.z.wf.core.service.WfSubscriptionService;
import com.zifang.z.wf.core.service.WfVariableService;
import com.zifang.z.wf.core.service.WfVariableQueryService;
import com.zifang.z.wf.core.service.WfFilterService;
import com.zifang.z.wf.core.service.WfActivityInstanceService;
import com.zifang.z.wf.core.service.WfManagementService;
import com.zifang.z.wf.core.service.WfExecutionQueryService;

/**
 * z-wf 引擎自动装配。
 *
 * <p>所有 Bean 都是 {@code @ConditionalOnMissingBean}，
 * 业务方可以逐个覆盖（例如换掉 {@link WfPersistence} 换自己的存储、
 * 注册自定义 {@link WfTaskHook}）。<b>引擎不与 Spring 强耦合</b>：
 * core 里的 engine / service 全是普通对象，不依赖容器也能 new 出来用
 * （这正是它能被 z-util-wf 那种"纯内存"形态复用或对比的前提）。
 *
 * <p>持久化选择：默认 {@code memory}（零依赖，测试与单机小应用直接能跑）；
 * 配 {@code z.wf.persistence=jdbc} 且容器里有 DataSource 时切到 JDBC。
 * <b>没有 DataSource 也不报错</b>，而是回落内存实现并打 WARN ——
 * 让人能在开发期先跑通，而不是启动直接失败。
 *
 * @author zifang
 */
@Configuration
@EnableConfigurationProperties(WfProperties.class)
@ConditionalOnProperty(prefix = "z.wf", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WfAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WfAutoConfiguration.class);

    // ==================== 持久化 ====================

    @Bean
    @ConditionalOnMissingBean
    public WfPersistence wfPersistence(WfProperties properties, ObjectProvider<DataSource> dataSource) {
        WfPersistence persistence;
        if ("jdbc".equalsIgnoreCase(properties.getPersistence())) {
            DataSource ds = dataSource.getIfAvailable();
            if (ds != null) {
                persistence = new JdbcWorkflowPersistence(ds);
                if (properties.isAutoInitializeSchema()) {
                    persistence.initialize();
                }
                log.info("z-wf 使用 JDBC 持久化");
                return persistence;
            }
            log.warn("z.wf.persistence=jdbc 但容器里没有 DataSource，回落到内存实现"
                    + "（流程数据不会落库，重启即丢）");
        }
        persistence = new InMemoryWorkflowPersistence();
        persistence.initialize();
        log.info("z-wf 使用内存持久化（z.wf.persistence 可改为 jdbc 落库）");
        return persistence;
    }

    // ==================== 引擎 ====================

    @Bean
    @ConditionalOnMissingBean
    public WfExpressionEvaluator wfExpressionEvaluator(WfProperties properties) {
        return new WfExpressionEvaluator(properties.isFailOpen());
    }

    @Bean
    @ConditionalOnMissingBean
    public WfBehaviorRegistry wfBehaviorRegistry() {
        return new WfBehaviorRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public WfIdGenerator wfIdGenerator() {
        return new WfIdGenerator.DefaultWfIdGenerator();
    }

    @Bean
    @ConditionalOnMissingBean
    public WfDelegateRegistry wfDelegateRegistry() {
        return new WfDelegateRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public WfEngine wfEngine(WfBehaviorRegistry behaviorRegistry,
                             WfExpressionEvaluator expressionEvaluator,
                             WfIdGenerator idGenerator,
                             WfDelegateRegistry delegateRegistry,
                             WfDecisionService decisionService) {
        // 决策服务用 with… 挂上去而不是多一个构造器参数：引擎有四个构造器，
        // 让每一处 new 都得为一个「多数部署用不上」的能力传参是强加的接线负担
        return new WfEngine(behaviorRegistry, expressionEvaluator, idGenerator, delegateRegistry)
                .withDecisionService(decisionService);
    }

    // ==================== 钩子 ====================

    /**
     * 钩子分发器 —— 自动收集容器里所有钩子实现。
     * <p>用 {@code ObjectProvider.stream()} 而不是 {@code getBeansOfType}，
     * 是为了在容器还没完全刷新时也能安全取（钩子 Bean 常常带 @DependsOn）。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfHookDispatcher wfHookDispatcher(ObjectProvider<WfProcessHook> processHooks,
                                              ObjectProvider<WfTaskHook> taskHooks,
                                              ObjectProvider<WfNotificationHook> notificationHooks) {
        WfHookDispatcher dispatcher = new WfHookDispatcher();
        int count = 0;
        for (WfProcessHook hook : processHooks.stream().toArray(WfProcessHook[]::new)) {
            dispatcher.addProcessHook(hook);
            count++;
        }
        for (WfTaskHook hook : taskHooks.stream().toArray(WfTaskHook[]::new)) {
            dispatcher.addTaskHook(hook);
            count++;
        }
        for (WfNotificationHook hook : notificationHooks.stream().toArray(WfNotificationHook[]::new)) {
            dispatcher.addNotificationHook(hook);
            count++;
        }
        log.info("z-wf 注册钩子 {} 个", count);
        return dispatcher;
    }

    // ==================== 历史故障（第 40 轮） ====================

    /**
     * 历史故障查询（第 40 轮）。
     *
     * <p><b>记录动作本身不通过这个 bean</b>：{@code WfJobService} 与
     * {@code WfExternalTaskService} 在构造时就地建了一份同类的记录器，
     * 因为那两条链路是热路径，为它加构造参数要动 21 处调用点。
     * 这里注册的是<b>查询侧</b>用的那一份 —— 与记录侧无状态、可互换，
     * 两边各自独立持有不会导致读到看不见的数据。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfHistoricIncidentService wfHistoricIncidentService(WfPersistence persistence) {
        return new WfHistoricIncidentService(persistence);
    }

    // ==================== 服务 ====================

    @Bean
    @ConditionalOnMissingBean
    public WfRepositoryService wfRepositoryService(WfPersistence persistence) {
        return new WfRepositoryService(persistence);
    }

    @Bean
    @ConditionalOnMissingBean
    public WfRuntimeService wfRuntimeService(WfRepositoryService repositoryService,
                                             WfPersistence persistence,
                                             WfEngine engine,
                                             WfHookDispatcher hookDispatcher,
                                             WfIdGenerator idGenerator) {
        return new WfRuntimeService(repositoryService, persistence, engine, hookDispatcher,
                idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean
    public WfTaskService wfTaskService(WfRepositoryService repositoryService,
                                       WfPersistence persistence,
                                       WfRuntimeService runtimeService,
                                       WfHookDispatcher hookDispatcher) {
        return new WfTaskService(repositoryService, persistence, runtimeService, hookDispatcher);
    }

    @Bean
    @ConditionalOnMissingBean
    public WfHistoryService wfHistoryService(WfPersistence persistence) {
        return new WfHistoryService(persistence);
    }

    @Bean
    @ConditionalOnMissingBean
    public WfVariableService wfVariableService(WfPersistence persistence,
                                               WfIdGenerator idGenerator) {
        return new WfVariableService(persistence, idGenerator);
    }

    /**
     * Job 执行器。
     *
     * <p>注册成 bean 但<b>不自带任何定时器</b>：扫多频繁是业务决定的事，
     * 引擎内嵌调度会让"引依赖就跑起来"成为默认行为。宿主用
     * {@code @Scheduled}、自己的调度中心，或测试里直接调都行。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfJobService wfJobService(WfPersistence persistence, WfRuntimeService runtimeService) {
        return new WfJobService(persistence, runtimeService);
    }

    /**
     * 批量操作（第 39 轮）。
     *
     * <p>依赖 runtime / variable / task 三个服务而不是自己直接改存储 ——
     * 批次改的是「一个已经跑起来的实例/任务」，而这些服务各自带一整套
     * 前置校验（终态实例不能挂起、办结任务不能改变量）与审计留痕。
     * 自己绕过它们直接 {@code save} 的话，批量改完的实例会缺一条
     * 「谁在什么时候改的」记录，而单条操作是有这条的 ——
     * 同一个动作两条路径两种留痕，排障时最费时间的就是这种不一致。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfBatchService wfBatchService(WfPersistence persistence,
                                         WfIdGenerator idGenerator,
                                         WfRuntimeService runtimeService,
                                         WfVariableService variableService,
                                         WfTaskService taskService) {
        return new WfBatchService(persistence, idGenerator, runtimeService,
                variableService, taskService);
    }

    /**
     * 超期待办扫描器。
     *
     * <p>注册成 Bean 但<b>不自带定时器</b>：扫描频率是业务决定的，
     * 由调用方用 {@code @Scheduled} 或组织自己的调度中心触发。
     * 引擎内嵌定时器会让"引依赖就跑起来了"变成默认行为，多数部署并不想要。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfOverdueScanner wfOverdueScanner(WfPersistence persistence,
                                             WfHookDispatcher hookDispatcher) {
        return new WfOverdueScanner(persistence, hookDispatcher);
    }

    /**
     * 外部任务服务。
     *
     * <p>与 {@link #wfJobService} 同样<b>不自带轮询器</b>：外部 worker 要不要常驻、
     * 拉取间隔多少，取决于业务上"外部动作能容忍多长的延迟"。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfExternalTaskService wfExternalTaskService(WfPersistence persistence,
                                                      WfRuntimeService runtimeService) {
        return new WfExternalTaskService(persistence, runtimeService);
    }

    /**
     * 订阅查询服务 —— "现在有哪些流程在等什么"。
     *
     * <p>纯读，不需要执行器也不需要定时器；宿主想接监控就自己定时调
     * {@code listSubscriptions}，不想接就只是个查询工具。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfSubscriptionService wfSubscriptionService(WfPersistence persistence,
                                                      WfRepositoryService repositoryService) {
        return new WfSubscriptionService(persistence, repositoryService);
    }

    /**
     * 活动实例树查询。纯读，不启动任何线程。
     *
     * <p>监控台"这条单现在走到哪了、并行分支在哪"走这个 bean；
     * 它与 {@code WfHistoryService} 的审批轨迹给的是同一批事实的两种切法，不互相推导。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfActivityInstanceService wfActivityInstanceService(
            WfPersistence persistence, WfRepositoryService repositoryService) {
        return new WfActivityInstanceService(persistence, repositoryService);
    }

    /**
     * 引擎自省（properties / 存储清单）。纯读，不启动任何线程。
     *
     * <p>刻意<b>不带任何配置</b>：它只问持久层"你有哪些存储项、各多少条"，
     * 至于底下是表还是进程内集合，由实现自己如实说。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfManagementService wfManagementService(WfPersistence persistence) {
        return new WfManagementService(persistence);
    }

    /**
     * 执行令牌的条件查询。纯读，不启动任何线程。
     *
     * <p>与 {@code WfSubscriptionService} 放在一起的原因：两者回答的是同一个问题的两半
     * ——「这条单子不动了」。订阅答「在等一个事件」，令牌答「停在哪一步」。
     * 分开部署时排障的人要打两个接口才能拼出一句完整的判断。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfExecutionQueryService wfExecutionQueryService(WfPersistence persistence) {
        return new WfExecutionQueryService(persistence);
    }

    /**
     * 运行期故障查询。纯读，不启动任何线程 ——
     * 要不要定时轮询由宿主决定（监控场景自己接一个定时任务调 {@code countIncidents}）。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfIncidentService wfIncidentService(WfPersistence persistence,
                                              WfRepositoryService repositoryService) {
        return new WfIncidentService(persistence, repositoryService);
    }

    /**
     * 变量实例查询。纯读，不启动任何线程。
     *
     * <p>与 {@link WfVariableService} 的分工：那个是<b>按名读写</b>某一个变量
     * （改动会落审计），这个是<b>按作用域查</b>有哪些变量（什么都不改）。
     * 两者都需要 —— 知道"有哪些"是排障的入口，知道"哪个挂在哪级"是排障的结论。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfVariableQueryService wfVariableQueryService(WfPersistence persistence,
                                                        WfRepositoryService repositoryService) {
        return new WfVariableQueryService(persistence, repositoryService);
    }

    /**
     * 保存筛选器。纯配置面，不启动任何线程。
     *
     * <p>依赖 {@link WfIncidentService} 而不是自己再实现一遍故障查询：
     * 筛选器"跑起来"时要用到故障的判定逻辑（失败过、订阅名、类型），
     * 那套判定住在 {@code WfIncidentService} 里。两处各写一份的话，
     * 改了一处另一处就悄悄给出不同结果 —— 而那是同一批数据的两种答案。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfFilterService wfFilterService(WfPersistence persistence,
                                           WfIncidentService incidentService) {
        return new WfFilterService(persistence, incidentService);
    }

    // ==================== DMN 决策 ====================

    /**
     * DMN 决策表服务。
     *
     * <p><b>刻意不放 evaluator 参数</b>：求值器内部没有可变状态，
     * 每处 new 一个与共用一个行为完全一样；而共用一个会让"这个服务被谁改过配置"
     * 变成一个需要排查的问题。
     */
    @Bean
    @ConditionalOnMissingBean
    public WfDecisionService wfDecisionService(WfPersistence persistence) {
        return new WfDecisionService(persistence);
    }

    // ==================== 启动部署 ====================

    /**
     * 启动时扫描并部署 classpath 下的流程定义。
     *
     * <p>单个定义部署失败<b>不阻断启动</b>：classpath 下常混着别的系统的流程文件，
     * 引擎应当把能用的都装上、把坏的打出来，而不是让整个服务起不来。
     */
    @Bean
    public WfProcessDeployer wfProcessDeployer(WfRepositoryService repositoryService,
                                               WfProperties properties) {
        WfProcessDeployer deployer = new WfProcessDeployer(repositoryService, properties);
        if (properties.isDeployOnStartup()) {
            deployer.deployFromClasspath();
        }
        return deployer;
    }
}
