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

    /**
     * 默认实现：{@code 前缀 + 进程启动随机数 + 自增序号}。
     *
     * <p>随机数取 JVM 启动时刻的毫秒偏移，重启后前缀必变 ⇒ 跨重启不撞号。
     */
    class DefaultWfIdGenerator implements WfIdGenerator {

        private final String instanceTag;
        private final AtomicLong processCounter = new AtomicLong();
        private final AtomicLong executionCounter = new AtomicLong();
        private final AtomicLong taskCounter = new AtomicLong();
        private final AtomicLong activityCounter = new AtomicLong();
        private final AtomicLong commentCounter = new AtomicLong();

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
            return "proc-" + instanceTag + "-" + processCounter.incrementAndGet();
        }

        @Override
        public String nextExecutionId() {
            return "exe-" + instanceTag + "-" + executionCounter.incrementAndGet();
        }

        @Override
        public String nextTaskId() {
            return "task-" + instanceTag + "-" + taskCounter.incrementAndGet();
        }

        @Override
        public String nextActivityId() {
            return "act-" + instanceTag + "-" + activityCounter.incrementAndGet();
        }

        @Override
        public String nextCommentId() {
            return "cmt-" + instanceTag + "-" + commentCounter.incrementAndGet();
        }
    }
}
