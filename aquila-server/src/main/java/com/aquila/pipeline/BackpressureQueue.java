package com.aquila.pipeline;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 治理/情感背压队列（W0.5；对应 04 §2 governQueue、§1.2 offer(docs,3,SECONDS)）。
 *
 * <p>有界队列 + 限时 offer：满则阻塞到超时后返回 false，由采集侧标记 {@code parse_status=0} 待补偿
 * （BacklogReplayer 每 60s 补投）。绝不整批丢弃、绝不无界增长（F-CRAWL / F-SENT 相关纪律）。</p>
 *
 * @param <T> 队列元素（如 NormalizedDoc 批次）
 */
public class BackpressureQueue<T> {

    private final LinkedBlockingQueue<T> queue;
    private final long offerTimeoutMs;

    public BackpressureQueue(int capacity, long offerTimeoutMs) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须为正");
        }
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.offerTimeoutMs = offerTimeoutMs;
    }

    /** 限时投递单个元素。@return true=入队成功；false=队列已满且超时。 */
    public boolean offer(T item) {
        try {
            return queue.offer(item, offerTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 批量限时投递。@return 全部入队返回 true；任一超时返回 false（已入队部分保留，调用方按 pending 补偿）。 */
    public boolean offerAll(List<T> batch) {
        for (T t : batch) {
            if (!offer(t)) {
                return false;
            }
        }
        return true;
    }

    /** 消费端阻塞取，带超时（poll(5s) 语义）。 */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    public int size() {
        return queue.size();
    }

    public int remainingCapacity() {
        return queue.remainingCapacity();
    }
}
