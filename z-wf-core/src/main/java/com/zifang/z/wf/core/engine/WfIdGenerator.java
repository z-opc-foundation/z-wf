package com.zifang.z.wf.core.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ID 生成器。
 *
 * <p>抽成接口是因为生产环境需要 DB 序列（多节点不能各自从 1 开始数），
 * 而内存/测试环境用自增就够。默认实现带<b>随机前缀</b>，避免重启后 ID 与上一轮进程
 * 撞号（自增计数器在重启后从 0 开始，若无前缀则 "task-1" 会与库里的旧 "task-1" 冲突）。
 *
 * @author zifang
 */
public interface WfIdGenerator {

    /** 生成流程实例 ID。 */
    String nextProcessInstanceId();

    /** 生成 token（execution）ID。 */
    String nextExecutionId();

    /** 生成任务 ID。 */
    String nextTaskId();

    /** 生成活动历史 ID。 */
    String nextActivityId();

    /** 生成评论 ID。 */
    String nextCommentId();

    /** 生成 job ID。 */
    String nextJobId();

    /**
     * 默认实现：{@code 前缀 + 进程启动随机数 + 定长自增序号}。
     *
     * <p>随机数取 JVM 启动时刻的毫秒偏移，重启后前缀必变 ⇒ 跨重启不撞号。
     *
     * <p><b>序号必须补零到定长</b>，这不是美观问题而是正确性问题：审计查询在多条记录
     * 同一毫秒时靠 {@code ORDER BY CMT_ID} 兜底保证分页稳定，而未补零的字符串比较里
     * {@code "cmt-x-9" > "cmt-x-10"}（'9' > '1'），一旦批量写入的变量数跨过两位数，
     * "越新越先看"的顺序就被打乱。补零后字典序 == 插入序，任何按 id 排序的地方都自动正确。
     */
    class DefaultWfIdGenerator implements WfIdGenerator {

        /** 序号位数：10^12 次，单 JVM 实例的量级绰绰有余。 */
        private static final int SEQ_WIDTH = 12;

        private final String instanceTag;
        private final AtomicLong processCounter = new AtomicLong();
        private final AtomicLong executionCounter = new AtomicLong();
        private final AtomicLong taskCounter = new AtomicLong();
        private final AtomicLong activityCounter = new AtomicLong();
        private final AtomicLong commentCounter = new AtomicLong();
        private final AtomicLong jobCounter = new AtomicLong();

        public DefaultWfIdGenerator() {
            // 启动期随机后缀：不同 JVM 实例/不同次启动互不相同
            this.instanceTag = Long.toHexString(System.currentTimeMillis())
                    + Integer.toHexString((int) (System.nanoTime() & 0xFFFFFF));
        }

        public DefaultWfIdGenerator(String instanceTag) {
            this.instanceTag = instanceTag;
        }

        @Override
        public String nextProcessInstanceId() {
            return "proc-" + instanceTag + "-" + pad(processCounter.incrementAndGet());
        }

        @Override
        public String nextExecutionId() {
            return "exe-" + instanceTag + "-" + pad(executionCounter.incrementAndGet());
        }

        @Override
        public String nextTaskId() {
            return "task-" + instanceTag + "-" + pad(taskCounter.incrementAndGet());
        }

        @Override
        public String nextActivityId() {
            return "act-" + instanceTag + "-" + pad(activityCounter.incrementAndGet());
        }

        @Override
        public String nextCommentId() {
            return "cmt-" + instanceTag + "-" + pad(commentCounter.incrementAndGet());
        }

        @Override
        public String nextJobId() {
            return "job-" + instanceTag + "-" + pad(jobCounter.incrementAndGet());
        }

        /** 左补零到 {@link #SEQ_WIDTH} 位；已超宽则原样返回（不能截断，会撞号）。 */
        private static String pad(long n) {
            String s = Long.toString(n);
            if (s.length() >= SEQ_WIDTH) {
                return s;
            }
            StringBuilder sb = new StringBuilder(SEQ_WIDTH);
            for (int i = s.length(); i < SEQ_WIDTH; i++) {
                sb.append('0');
            }
            return sb.append(s).toString();
        }
    }
}
