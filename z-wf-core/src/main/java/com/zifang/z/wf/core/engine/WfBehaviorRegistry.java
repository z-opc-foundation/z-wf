package com.zifang.z.wf.core.engine;

import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zifang.z.wf.core.definition.WfNodeType;
import com.zifang.z.wf.core.engine.behavior.WfActivityBehavior;
import com.zifang.z.wf.core.engine.behavior.WfCallActivityBehavior;
import com.zifang.z.wf.core.engine.behavior.WfDefaultBehavior;
import com.zifang.z.wf.core.engine.behavior.WfEndEventBehavior;
import com.zifang.z.wf.core.engine.behavior.WfGatewayBehavior;
import com.zifang.z.wf.core.engine.behavior.WfLinkCatchBehavior;
import com.zifang.z.wf.core.engine.behavior.WfReceiveTaskBehavior;
import com.zifang.z.wf.core.engine.behavior.WfThrowEventBehavior;
import com.zifang.z.wf.core.engine.behavior.WfScriptTaskBehavior;
import com.zifang.z.wf.core.engine.behavior.WfServiceTaskBehavior;
import com.zifang.z.wf.core.engine.behavior.WfStartEventBehavior;
import com.zifang.z.wf.core.engine.behavior.WfUserTaskBehavior;

/**
 * 节点行为注册表 —— 节点类型 → 行为的映射。
 *
 * <p>默认装配覆盖 z-wf 全部内置节点类型；业务方可用 {@link #register} 覆盖某个类型的行为
 * （例如把 userTask 换成"自动建任务 + 自动通知"的定制实现），或用 {@link #registerExtension}
 * 追加全新类型。
 *
 * <p>未被注册的类型回落到 {@link WfDefaultBehavior}（不建任务、直接通过），
 * 保证未知节点类型不会让流程崩掉——设计器加了新类型时，流程仍能跑通到下一个已支持节点。
 *
 * @author zifang
 */
public class WfBehaviorRegistry {

    private static final Logger log = LoggerFactory.getLogger(WfBehaviorRegistry.class);

    private final Map<WfNodeType, WfActivityBehavior> behaviors =
            new EnumMap<WfNodeType, WfActivityBehavior>(WfNodeType.class);

    private final WfActivityBehavior fallback = new WfDefaultBehavior();

    /**
     * 默认装配。
     */
    public WfBehaviorRegistry() {
        registerDefaults();
    }

    private void registerDefaults() {
        register(WfNodeType.START_EVENT, new WfStartEventBehavior());
        register(WfNodeType.END_EVENT, new WfEndEventBehavior());
        register(WfNodeType.USER_TASK, new WfUserTaskBehavior());
        register(WfNodeType.MANUAL_TASK, new WfUserTaskBehavior());
        register(WfNodeType.TASK, new WfUserTaskBehavior());
        register(WfNodeType.SERVICE_TASK, new WfServiceTaskBehavior());
        register(WfNodeType.SCRIPT_TASK, new WfScriptTaskBehavior());
        register(WfNodeType.SEND_TASK, new WfServiceTaskBehavior());
        register(WfNodeType.RECEIVE_TASK, new WfReceiveTaskBehavior());
        register(WfNodeType.THROW_EVENT, new WfThrowEventBehavior());
        register(WfNodeType.LINK_CATCH, new WfLinkCatchBehavior());
        // LINK_THROW 刻意**不注册**：它的全部语义是「把 token 改道到 catch」，
        // 而改道发生在**离开**节点时（见 WfEngine#jumpToLinkCatch），不是进入时。
        // 进入阶段它不该做任何动作，落到兜底行为（返回 null 直接通过）正是这里要的语义。
        // 给它再注册一个"什么都不做"的类，只会多出第二份"什么都不做"的写法 ——
        // 将来若有人照着 catch 注册一行，LINK_THROW 看起来就有了"被实现过"的证据，
        // 而真正该改的地方（leave 里那道特判）并没有被碰。
        register(WfNodeType.EXCLUSIVE_GATEWAY, new WfGatewayBehavior());
        register(WfNodeType.PARALLEL_GATEWAY, new WfGatewayBehavior());
        register(WfNodeType.INCLUSIVE_GATEWAY, new WfGatewayBehavior());
        register(WfNodeType.SUB_PROCESS, new WfCallActivityBehavior());
        register(WfNodeType.CALL_ACTIVITY, new WfCallActivityBehavior());
    }

    /**
     * 注册/覆盖某类型的内置行为。
     */
    public void register(WfNodeType type, WfActivityBehavior behavior) {
        if (type == null || behavior == null) {
            return;
        }
        behaviors.put(type, behavior);
        log.debug("注册节点行为: {} -> {}", type, behavior.getClass().getSimpleName());
    }

    /**
     * 取行为；未注册时返回兜底行为。
     */
    public WfActivityBehavior getBehavior(WfNodeType type) {
        WfActivityBehavior behavior = behaviors.get(type);
        if (behavior == null) {
            log.debug("节点类型 {} 未注册行为，使用兜底实现", type);
            return fallback;
        }
        return behavior;
    }

    /**
     * 已注册的类型数（诊断用）。
     */
    public int size() {
        return behaviors.size();
    }
}
