package com.zifang.z.wf.core.definition;

/**
 * {@code <dataObject>} 的作用域（第 46 轮）。
 *
 * <p><b>BPMN 里这个元素没有 {@code scope} 属性</b> —— 作用域是由「它被声明在哪儿」
 * 决定的：直接写在 {@code <process>} 下的 {@code dataObject} 是<b>流程级</b>，
 * 写在 {@code <subProcess>} 下的是<b>阶段级</b>（{@code stage}）。
 *
 * <p>把这份信息显式建模出来而不是丢掉的理由：它决定这份数据的<b>有效期</b>。
 * 流程级的活到实例结束为止，阶段级的活到那个子流程结束为止 ——
 * 建模工具与流程走查工具都要靠它判断"这个变量在子流程外还存不存在"，
 * 而 XML 里这件事只能靠"数一数它嵌在几层里"得到。解析器顺手就能算出来。
 *
 * @author zifang
 */
public enum WfDataScope {

    /** 直接声明在 {@code <process>} 下 —— 活到流程实例结束。 */
    PROCESS,

    /**
     * 声明在某个 {@code <subProcess>} / {@code <transaction>} / {@code <adHocSubProcess>} 下 ——
     * 活到那个容器作用域结束。
     *
     * <p>刻意<b>不细分是哪一种容器</b>：作用域的<b>长度</b>只取决于嵌了几层，
     * 容器是什么类型不影响"这份数据什么时候失效"。真要知道容器类型，
     * 按 {@code WfDataObject#getId()} 回到节点表里查父容器即可。
     */
    STAGE
}
