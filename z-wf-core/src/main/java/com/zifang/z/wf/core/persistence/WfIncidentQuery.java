package com.zifang.z.wf.core.persistence;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import com.zifang.z.wf.core.model.WfJobType;

/**
 * 运行期故障查询条件 —— "现在有哪些事情没干成"。
 *
 * <p>与 {@link WfSubscriptionQuery} 同构，且刻意保持同构：排障时这两个问题
 * 往往是一起问的（"这批单子是在等，还是已经炸了"），两套形状不一样的查询对象
 * 会让调用方每次都要重新想一遍怎么传条件。
 *
 * <p><b>最常用的是 {@link #setRetriesExhausted(Boolean)}</b>，而不是不带条件全查。
 * "还在重试"与"彻底不动了"是两种处置：前者可以放着等，后者必须人去看。
 * 不带条件查回来的列表里两者混在一起，运维得逐条判断该管哪条。
 *
 * @author zifang
 */
public class WfIncidentQuery {

    /** 限定某个流程实例。 */
    private String processInstanceId;

    /** 限定某个流程定义 key。 */
    private String definitionKey;

    /** 只看这些 job 类型；为空表示不限。 */
    private Set<WfJobType> types = new HashSet<WfJobType>();

    /** 只看这个节点上的（对边界事件来说是边界事件自己的 id）。 */
    private String activityId;

    /**
     * 只看重试已耗尽的（{@code true}），或只看还能重试的（{@code false}）；null 表示不限。
     *
     * <p>{@link WfJob#isRetriesExhausted()} 用的是 {@code <= 0}，
     * 而扣减到负数只是 {@link WfJob#RETRIES_EXHAUSTED} 这个下限标记，
     * 不是"还能再试 -2 次"。
     */
    private Boolean retriesExhausted;

    /**
     * 只看 {@code lastFailureTime} 在此之前的。
     *
     * <p>用来找"失败很久了还没人管"的。不用 {@code now - failedSinceMillis}
     * 那种相对写法，是因为排障页刷新一次语义就得重新解释一次，
     * 而"1 月 3 日之后失败的一直没处理"这种说法在工单里是直接可抄的。
     */
    private Date failedBefore;

    /**
     * 错误信息包含这个子串的。
     *
     * <p>用于按下游系统归类故障："哪些单子是因为 ERP 超时挂的"。
     * 刻意做子串而不是等值 —— {@code recordFailure} 存的是
     * {@code 异常类名 + ": " + 消息}，等值匹配几乎永远匹配不上。
     */
    private String errorMessageContains;

    private int pageNum = 1;

    private int pageSize = 50;

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public WfIncidentQuery setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
        return this;
    }

    public String getDefinitionKey() {
        return definitionKey;
    }

    public WfIncidentQuery setDefinitionKey(String definitionKey) {
        this.definitionKey = definitionKey;
        return this;
    }

    public Set<WfJobType> getTypes() {
        return types;
    }

    public WfIncidentQuery setTypes(Set<WfJobType> types) {
        this.types = types == null ? new HashSet<WfJobType>() : types;
        return this;
    }

    public WfIncidentQuery addType(WfJobType type) {
        if (type != null) {
            this.types.add(type);
        }
        return this;
    }

    public String getActivityId() {
        return activityId;
    }

    public WfIncidentQuery setActivityId(String activityId) {
        this.activityId = activityId;
        return this;
    }

    public Boolean getRetriesExhausted() {
        return retriesExhausted;
    }

    public WfIncidentQuery setRetriesExhausted(Boolean retriesExhausted) {
        this.retriesExhausted = retriesExhausted;
        return this;
    }

    public Date getFailedBefore() {
        return failedBefore;
    }

    public WfIncidentQuery setFailedBefore(Date failedBefore) {
        this.failedBefore = failedBefore;
        return this;
    }

    public String getErrorMessageContains() {
        return errorMessageContains;
    }

    public WfIncidentQuery setErrorMessageContains(String errorMessageContains) {
        this.errorMessageContains = errorMessageContains;
        return this;
    }

    public int getPageNum() {
        return pageNum;
    }

    public WfIncidentQuery setPageNum(int pageNum) {
        this.pageNum = pageNum;
        return this;
    }

    public int getPageSize() {
        return pageSize;
    }

    public WfIncidentQuery setPageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    public int normalizedPageNum() {
        return pageNum < 1 ? 1 : pageNum;
    }

    public int normalizedPageSize() {
        if (pageSize < 1) {
            return 50;
        }
        return Math.min(pageSize, 1000);
    }
}