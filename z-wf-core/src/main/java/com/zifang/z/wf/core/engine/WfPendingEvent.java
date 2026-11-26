package com.zifang.z.wf.core.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条<b>待投递</b>的抛事件 —— 由 {@code intermediateThrowEvent} 在 token 走到它时登记，
 * 由 {@code WfRuntimeService} 在<b>本次事务落库之后</b>真正投出去。
 *
 * <p><b>为什么不在 behavior 里当场投。</b>behavior 跑在单个流程实例的事务内部，
 * 而抛事件要唤醒的是<b>别的</b>流程实例。若在事务中途投出去，被唤醒的那条会读到
 * <b>尚未落库</b>的旧状态并据此推进，随后外层把新状态写回 ——
 * 内层的推进被外层覆盖，看起来像"事件到了但流程没动"，而且不报错。
 * 这就是典型的丢更新，而它的现场没有任何异常。
 * ⇒ 投递必须发生在 {@code persistAll} 之后，那时状态才是真的。
 *
 * @author zifang
 */
public class WfPendingEvent {

    /**
     * 投递方式。信号是广播，消息是点对点，升级是广播。
     *
     * <p>升级与信号同为广播，<b>但不是同一件事</b>：升级要去动宿主上的待办
     * （可能换人、可能作废），而信号只是叫醒一条分支。分成两个枚举值而不是
     * "看名字长得像不像"，是为了让投递路径的选择变成一次显式的判断。
     */
    public enum Kind {
        SIGNAL, MESSAGE, ESCALATION
    }

    private final Kind kind;

    private final String eventName;

    /** 抛事件节点 id，出现在评论与报错里。 */
    private final String sourceActivityId;

    private final String sourceProcessInstanceId;

    /**
     * 随事件带出去的变量。
     *
     * <p>复制一份而不是直接引用流程变量：投递发生在外层事务之后，
     * 那一刻上下文里的 map 可能已经被后续推进改过。
     */
    private final Map<String, Object> variables = new LinkedHashMap<>();

    public WfPendingEvent(Kind kind, String eventName, String sourceActivityId,
                          String sourceProcessInstanceId, Map<String, Object> variables) {
        this.kind = kind;
        this.eventName = eventName;
        this.sourceActivityId = sourceActivityId;
        this.sourceProcessInstanceId = sourceProcessInstanceId;
        if (variables != null) {
            this.variables.putAll(variables);
        }
    }

    public Kind getKind() {
        return kind;
    }

    public String getEventName() {
        return eventName;
    }

    public String getSourceActivityId() {
        return sourceActivityId;
    }

    public String getSourceProcessInstanceId() {
        return sourceProcessInstanceId;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    @Override
    public String toString() {
        return kind + "[" + eventName + "]@" + sourceActivityId;
    }
}