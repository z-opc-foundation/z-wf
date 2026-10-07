package com.zifang.z.wf.core.definition;

import java.io.Serializable;

/**
 * {@code <dataStore>} —— 流程声明要用的<b>持久化数据存储</b>（第 46 轮）。
 *
 * <pre>{@code
 * <dataStore id="ds1" name="订单库" capacity="1000" isUnlimited="true"/>
 * }</pre>
 *
 * <p><b>本引擎完全不对它做存取，一个字节都不碰。</b>
 * 这不是遗漏，是这一轮<b>先评估再决定</b>的结论，理由如下。
 *
 * <p><b>① Camunda 7 也不实现它。</b> 引擎侧只把 {@code dataStore} 读进 BPMN 模型
 * 供建模工具与校验使用，数据的读写由外部系统（那个库/那个服务）自己完成。
 * 「流程引擎该连数据库执行读写」这个预期本身就不成立 ——
 * BPMN 从没规定它该用哪个协议、哪种序列化、怎么管事务。
 *
 * <p><b>② 硬做会比不做更坏。</b> 引擎要真去存，就得回答"存哪儿、谁建表、
 * 数据结构怎么序列化、并发怎么锁、失败怎么回滚"这一串问题，
 * 而这些在 BPMN 里<b>一个都没有</b>。任填一种都会造出一个"看着能用、换个场景就坏"的
 * 私有数据层，且与已有的流程变量体系（{@code WfVariableService}）重复一套。
 *
 * <p><b>③ 真正让数据流起来的是 delegate，不是 dataStore。</b>
 * 业务数据在流程里搬运，一律走流程变量 + delegate 读写 —— 这条路本引擎是通的。
 *
 * <p><b>所以本类的全部价值是「读得进来、看得见、写得清楚」</b>：
 * 部署期它不报错（一个声明而已，不该挡别人的部署），
 * 但 REST 视图会把它连同 {@link WfDataObject} 一起吐出来，
 * 让走查工具能回答"这个流程声明了哪些外部数据源、引擎会不会真的去用它"。
 *
 * <p><b>改动前这块是静默丢弃</b>：解析器里没有任何一处读 {@code dataStore}，
 * 也不在「不支持元素」清单里 ⇒ 写了等于没写，一个错都不报。
 *
 * @author zifang
 */
public class WfDataStore implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 声明 id。 */
    private String id;

    /** 显示名。 */
    private String name;

    /**
     * {@code capacity} —— 声明的容量上限。
     *
     * <p>存成 {@link Integer} 而不是 {@code int}：BPMN 里这一项可选，
     * 而「作者没写」与「作者写了个数」在 {@code int} 上分不出来。
     * 分不出来就没法在 REST 视图里如实回显 —— 那是把"没配"显示成"配了 0"。
     *
     * <p><b>它不约束任何东西</b>：没有东西会拿它做检查，写它只是为了不丢信息。
     */
    private Integer capacity;

    /**
     * {@code isUnlimited} —— 作者是否声明「不限容量」。
     *
     * <p>BPMN 的默认值是 {@code false}，所以这里默认 {@code false}。
     * 它与 {@link #capacity} <b>可能自相矛盾</b>（{@code capacity="10" isUnlimited="true"}），
     * 但那属于建模表达问题而不是错误 —— 引擎两边都不执行，矛盾不产生任何行为差异，
     * 因此<b>刻意不报错</b>：报一个"不产生任何后果"的错，只会让迁移别人的模型时
     * 多一批挡路的理由。
     */
    private boolean unlimited;

    public WfDataStore() {
    }

    public WfDataStore(String id, String name, Integer capacity, boolean unlimited) {
        this.id = id;
        this.name = name;
        this.capacity = capacity;
        this.unlimited = unlimited;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public boolean isUnlimited() {
        return unlimited;
    }

    public void setUnlimited(boolean unlimited) {
        this.unlimited = unlimited;
    }

    @Override
    public String toString() {
        return "WfDataStore{" + id + ": " + name + ", capacity=" + capacity
                + ", unlimited=" + unlimited + "}";
    }
}
