package com.zifang.z.wf.core.definition.dmn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 决策依赖图的<b>环检测</b> —— {@code ensureNoLoopInDecisions} 的对应物。
 *
 * <p>决策图允许 A 依赖 B、B 依赖 C，但<b>不允许成环</b>：成环的图在求值时
 *   会一直要下一个决策的下游决策，最后要么栈溢出，要么在每一跳之间反复取到
 * 同一个"算了一半"的决策 —— 两种表现都不是报错，而是一张算出结果的表。
 *
 * <p><b>环必须在部署期挡掉，不能留到求值期</b>：求值期能发现"我回到了我"
 * （{@code WfDecisionService} 里有那道防线），但那时这张图已经落库了，
 * 而它可能已经被别的流程定义引用。让有环的图进库，症状是
 * 「某天某个流程跑到这张决策就卡死」，且与部署它的那次操作毫无关系。
 *
 * <p><b>本类不碰持久化</b>，只吃一张邻接表。这样它能被两处复用：
 * {@code WfDecisionService#parseDecision}（只用本文件内的边）与
 * {@code #deployDecision}（本文件 + 库里已部署的边）。
 *
 * @author zifang
 */
public final class WfDecisionDependency {

    /** 未访问。 */
    private static final int WHITE = 0;
    /** 在当前 DFS 路径上 —— 再遇到它就是环。 */
    private static final int GRAY = 1;
    /** 已确认它通往无环的子图。 */
    private static final int BLACK = 2;

    private WfDecisionDependency() {
    }

    /**
     * 在给定邻接表里找一个环。
     *
     * <p><b>调用方必须自己把图闭上。</b>表里<b>没有</b>出现的 key 被当作叶子 ——
     * 也就是"我只知道它依赖了谁，不知道它自己还依赖了谁"。
     * 于是拿一份<b>只含本文件</b>的表来查，会漏掉跨文件的环：
     *
     * <pre>
     *   文件 A：decision X 依赖 Y
     *   文件 B：decision Y 依赖 X
     *   两份文件单独看都没有环，合起来有。
     * </pre>
     *
     * <p>这正是 {@code WfDecisionService#deployDecision} 要把库里已部署决策的
     * 依赖也并进表里的原因（见那里的 {@code collectEdges}）。漏了这一步的后果
     * 与不检测环完全一样，而且更隐蔽：单文件测试全绿，只有两份文件都部署过的
     * 环境才会出问题。
     *
     * @param edges 决策 key → 它依赖的决策 key 列表；可以没有某个 key 的条目
     *              （视为叶子）
     * @return 形如 {@code [A, B, C, A]} 的环路径（首尾同一个 key）；
     *         无环时返回 {@code null}
     */
    public static List<String> findCycle(Map<String, List<String>> edges) {
        Map<String, Integer> colors = new HashMap<String, Integer>();
        List<String> stack = new ArrayList<String>();
        for (String key : edges.keySet()) {
            List<String> cycle = visit(key, edges, colors, stack);
            if (cycle != null) {
                return cycle;
            }
        }
        return null;
    }

    private static List<String> visit(String key, Map<String, List<String>> edges,
                                     Map<String, Integer> colors, List<String> stack) {
        Integer color = colors.get(key);
        if (color != null && color == BLACK) {
            return null;
        }
        if (color != null && color == GRAY) {
            // 又回到路径上的某个节点 —— 从它在栈里的位置起就是环。
            // 栈是自底向上 add 的，所以正序读出来就是 A -> B -> C 的依赖方向。
            List<String> cycle = new ArrayList<String>();
            boolean started = false;
            for (String node : stack) {
                if (node.equals(key)) {
                    started = true;
                }
                if (started) {
                    cycle.add(node);
                }
            }
            cycle.add(key);
            return cycle;
        }
        colors.put(key, GRAY);
        stack.add(key);
        List<String> dependencies = edges.get(key);
        if (dependencies != null) {
            for (String dependency : dependencies) {
                List<String> cycle = visit(dependency, edges, colors, stack);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        stack.remove(stack.size() - 1);
        colors.put(key, BLACK);
        return null;
    }
}