package com.zifang.z.wf.core.engine.behavior;

import com.zifang.z.wf.core.definition.WfNode;
import com.zifang.z.wf.core.engine.WfContext;
import com.zifang.z.wf.core.engine.WfSubProcessLauncher;
import com.zifang.z.wf.core.model.WfExecution;
import com.zifang.z.wf.core.model.WfTask;

/**
 * 子流程 / 调用活动行为。
 *
 * <p>子流程在本引擎里是一个<b>独立的子流程实例</b>（有自己的一级 token 树和实例 ID），
 * 而不是把子图"内联展开"到父实例的 token 树里。选独立实例的理由：
 * <ul>
 *   <li>子流程可能运行很久（几天），期间父流程可能被终止 —— 内联就没法干净地拆开</li>
 *   <li>子流程自己的待办/轨迹需要能被独立查询（审批场景里常问"这个子流程走到哪了"）</li>
 *   <li>内联展开会让父 token 树在有子流程时形态复杂化，并行/包容网关的汇合判定会出错</li>
 * </ul>
 * 代价是父子关联要显式记（父 token 持有子实例 id 列表），且要处理"子流程结束通知父流程"这条回调链。
 *
 * <p>{@link WfSubProcessLauncher} 由 {@code WfRuntimeService} 实现并注入 context；
 * 为 {@code null} 时（引擎被单独使用、无 repository）本行为空转，token 直接往下走。
 *
 * @author zifang
 */
public class WfCallActivityBehavior implements WfActivityBehavior {

    @Override
    public WfTask execute(WfContext context, WfNode node, WfExecution execution) {
        WfSubProcessLauncher launcher = context.getSubProcessLauncher();
        if (launcher != null && node.getCalledElementKey() != null
                && !node.getCalledElementKey().trim().isEmpty()) {
            launcher.launch(context, node, execution);
        }
        return null;
    }
}
