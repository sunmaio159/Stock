package com.aquila.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W0.5：验证治理背压队列 —— 满则限时 offer 返回 false（触发 pending 补偿），poll 正常消费。
 */
class BackpressureQueueTest {

    @Test
    void offer_returnsFalse_whenFullAndTimeoutElapsed() {
        BackpressureQueue<String> q = new BackpressureQueue<>(2, 50); // 超时 50ms
        assertTrue(q.offer("a"));
        assertTrue(q.offer("b"));
        assertEquals(0, q.remainingCapacity());

        long t0 = System.nanoTime();
        assertFalse(q.offer("c"), "队列满且超时后必须返回 false");
        long costMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(costMs >= 40, "应阻塞至超时才返回, cost=" + costMs + "ms");
    }

    @Test
    void offerAll_stopsAtFirstTimeout() {
        BackpressureQueue<String> q = new BackpressureQueue<>(1, 30);
        assertFalse(q.offerAll(List.of("x", "y")), "批量投递在队满处返回 false");
        assertEquals(1, q.size(), "首个元素已入队");
    }

    @Test
    void poll_returnsElement() throws InterruptedException {
        BackpressureQueue<String> q = new BackpressureQueue<>(10, 100);
        q.offer("hello");
        assertEquals("hello", q.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BackpressureQueue<String>(0, 100));
    }
}
