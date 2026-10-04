package com.aquila.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W0.5：验证采集执行池 AbortPolicy —— 队列满即拒绝、绝不阻塞提交线程（F-CRAWL-04）。
 */
class CrawlExecPoolTest {

    private CrawlExecPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    void rejectsWithoutBlocking_whenQueueFull() throws InterruptedException {
        pool = new CrawlExecPool(1, 1); // 1 线程 + 1 队列槽
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        // 占满 worker
        assertTrue(pool.submit(() -> {
            started.countDown();
            try {
                block.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }));
        started.await();

        // 占满队列
        assertTrue(pool.submit(() -> {
        }), "第二个任务应进入有界队列");

        // 队列+池都满 → 第三个被拒绝（返回 false，不抛、不阻塞）
        long t0 = System.nanoTime();
        assertFalse(pool.submit(() -> {
        }), "队列已满时提交必须返回 false（AbortPolicy）");
        long costMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(costMs < 100, "拒绝必须即时返回，不得阻塞提交线程, cost=" + costMs + "ms");

        assertEquals(1, pool.skippedCount(), "被拒任务应计入 skipped 指标");
        block.countDown();
    }

    @Test
    void rejectsNonPositiveConfig() {
        assertThrows(IllegalArgumentException.class, () -> new CrawlExecPool(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new CrawlExecPool(4, 0));
    }
}
