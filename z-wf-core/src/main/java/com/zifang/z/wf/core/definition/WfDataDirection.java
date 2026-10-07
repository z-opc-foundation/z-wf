package com.zifang.z.wf.core.definition;

/**
 * 数据关联的方向（第 46 轮）—— 对应 BPMN 的两种元素。
 *
 * <p><b>为什么必须是两个值而不是一个布尔</b>：方向决定了
 * <b>哪一端该指向什么</b>，这是 {@code WfDefinitionValidator} 唯一的类型判据 ——
 *
 * <ul>
 *   <li>{@link #INPUT}（{@code <dataInputAssociation>}）：
 *       {@code sourceRef} 可以是 {@code dataObject} 或 data object reference，
 *       而 {@code targetRef} <b>必须是</b> data object reference（它是"这个活动要读哪个数据"）</li>
 *   <li>{@link #OUTPUT}（{@code <dataOutputAssociation>}）：
 *       {@code sourceRef} <b>必须是</b> {@code dataObject}，
 *       {@code targetRef} 可以是 {@code dataObject} 或 data object reference
 *       （它是"这个活动算出来的数据交给谁"）</li>
 * </ul>
 *
 * <p>两端规则不对称，是 BPMN 2.0 规范本身如此（{@code tDataInputAssociation} 与
 * {@code tDataOutputAssociation} 的 {@code sourceRef} 基数不同）。
 * 合成一个布尔会丢掉这个不对称，部署期就没法报"input 的 targetRef 写成了 dataObject"，
 * 而那种配置在 Camunda 里能部署、在本引擎也不该被静默放过。
 *
 * @author zifang
 */
public enum WfDataDirection {

    /** {@code <dataInputAssociation>} —— 把数据<b>喂进</b>活动。 */
    INPUT,

    /** {@code <dataOutputAssociation>} —— 从活动<b>产出</b>数据。 */
    OUTPUT
}
