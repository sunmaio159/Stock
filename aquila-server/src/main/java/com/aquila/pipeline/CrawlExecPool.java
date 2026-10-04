package com.aquila.pipeline;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 采集执行池（W0.5 定稿骨架；对应 04 §1.2 execPool）。
 *
 * <p>铁律 F-CRAWL-04：扫描线程绝不能被执行池阻塞。
 * 采用 core=max=poolSize、有界队列 queueCapacity、{@link ThreadPoolExecutor.AbortPolicy}：
 * 队列满即抛 {@link RejectedExecutionException}，本类捕获后计入 skipped 指标并返回 false，
 * 由调度器下一轮再投；<b>禁止</b>改成 CallerRunsPolicy 或无界队列。</p>
 */
public class CrawlExecPool {

    private final ThreadPoolExecutor executor;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();

    public CrawlExecPool(int poolSize, int queueCapacity) {
        if (poolSize <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("poolSize/queueCapacity 必须为正");
        }
        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 提交采集任务。
     *
     * @return true=已入队/执行；false=队列已满被拒绝（调用方应记指标、下轮重试，绝不阻塞）
     */
    public boolean submit(Runnable task) {
        try {
            executor.execute(task);
            submitted.incrementAndGet();
            return true;
        } catch (RejectedExecutionException e) {
            skipped.incrementAndGet();
            return false;
        }
    }

    public long submittedCount() {
        return submitted.get();
    }

    public long skippedCount() {
        return skipped.get();
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
