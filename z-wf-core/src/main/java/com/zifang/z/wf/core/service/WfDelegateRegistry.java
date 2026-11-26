package com.zifang.z.wf.core.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.engine.delegate.WfJavaDelegate;

/**
 * Delegate 解析器 —— 把 serviceTask 节点上的 {@code delegateClass} /
 * {@code delegateExpression} 解析成可执行的 {@link WfJavaDelegate}。
 *
 * <p><b>「表达式优先于类」是本实现的兜底顺序，Camunda 没有这条规则</b> ——
 * 第 31 轮查出这里原先把它说成了对齐 Camunda，那是错的。Camunda 官方 BPMN 2.0 参考
 * Service Task 一节写的是：<b>「the three extensions camunda:class, camunda:expression,
 * and camunda:delegateExpression are mutually exclusive. The process engine will use
 * only one.」</b>也就是说 Camunda 不给优先级，它要求<b>只能配一个</b>。
 * <p>本实现同样在部署期把两者同时配的情况报 ERROR（见
 * {@code WfDefinitionValidator}），所以这条顺序**只在校验被绕过时才会被读到**：
 * 先表达式后类，是因为 Spring 环境下按 Bean 名取实现比按全限定名反射更可控 ——
 * 类名可以在流程定义里被篡改成任意有副作用的类，Bean 名不行。
 * 与 {@code WfNode#isCompetingJoin} 同型：兜底方向必须选**更保守**的那个。
 *
 * <p>实例缓存：同一类 delegate 只实例化一次（无状态单例约定）。
 * 若业务方需要每次新建，让实现类自己别假设是单例，或在 {@link #register} 时显式说明。
 *
 * @author zifang
 */
public class WfDelegateRegistry {

    private static final Logger log = LoggerFactory.getLogger(WfDelegateRegistry.class);

    /** delegateClass 全限定名 → 实例。 */
    private final Map<String, WfJavaDelegate> classCache = new ConcurrentHashMap<>();

    /**
     * 按 Bean 名/表达式注册的 delegate（优先级高于 classCache）。
     */
    private final Map<String, WfJavaDelegate> namedDelegates = new ConcurrentHashMap<>();

    /**
     * 解析节点对应的 delegate。
     *
     * @return delegate；未配置或解析失败返回 {@code null}
     */
    public WfJavaDelegate resolve(WfContext context, WfNode node) {
        // 1) 显式注册的 Bean 名
        Object expression = node.property("delegateExpression");
        if (expression == null && node.getDelegateExpression() != null) {
            expression = node.getDelegateExpression();
        }
        if (expression != null) {
            String name = String.valueOf(expression).trim();
            if (!name.isEmpty()) {
                WfJavaDelegate delegate = namedDelegates.get(name);
                if (delegate != null) {
                    return delegate;
                }
                // 支持 Spring 风格 "#{beanName}" 包装
                if (name.startsWith("#{") && name.endsWith("}")) {
                    name = name.substring(2, name.length() - 1).trim();
                    delegate = namedDelegates.get(name);
                    if (delegate != null) {
                        return delegate;
                    }
                }
                log.warn("delegateExpression '{}' 未在 WfDelegateRegistry 中注册", expression);
                return null;
            }
        }

        // 2) 按类全限定名反射
        String className = node.getDelegateClass();
        if (className == null || className.trim().isEmpty()) {
            return null;
        }
        className = className.trim();

        WfJavaDelegate cached = classCache.get(className);
        if (cached != null) {
            return cached;
        }

        try {
            Class<?> type = Class.forName(className, true, resolveClassLoader());
            Object instance = type.newInstance();
            if (!(instance instanceof WfJavaDelegate)) {
                log.error("{} 未实现 WfJavaDelegate", className);
                return null;
            }
            WfJavaDelegate delegate = (WfJavaDelegate) instance;
            classCache.put(className, delegate);
            return delegate;
        } catch (ClassNotFoundException e) {
            log.error("delegateClass 找不到类: {}", className, e);
            return null;
        } catch (InstantiationException e) {
            log.error("delegateClass 实例化失败（可能缺无参构造）: {}", className, e);
            return null;
        } catch (IllegalAccessException e) {
            log.error("delegateClass 不可访问（构造方法或类不是 public）: {}", className, e);
            return null;
        }
    }

    /**
     * 显式注册 delegate（按名）。
     */
    public void register(String name, WfJavaDelegate delegate) {
        if (name != null && delegate != null) {
            namedDelegates.put(name, delegate);
        }
    }

    private ClassLoader resolveClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : WfDelegateRegistry.class.getClassLoader();
    }
}
