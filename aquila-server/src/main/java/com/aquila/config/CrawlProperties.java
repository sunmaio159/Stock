package com.aquila.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 采集与治理流水线参数（源：04 §7 配置基线；06 W0.5）。
 */
@ConfigurationProperties(prefix = "aquila.crawl")
public class CrawlProperties {
    private int execPool = 8;
    private int execQueue = 64;
    private List<Long> retry = List.of(1000L, 2000L, 4000L);
    private int scanIntervalSeconds = 30;

    public int getExecPool() {
        return execPool;
    }

    public void setExecPool(int execPool) {
        this.execPool = execPool;
    }

    public int getExecQueue() {
        return execQueue;
    }

    public void setExecQueue(int execQueue) {
        this.execQueue = execQueue;
    }

    public List<Long> getRetry() {
        return retry;
    }

    public void setRetry(List<Long> retry) {
        this.retry = retry;
    }

    public int getScanIntervalSeconds() {
        return scanIntervalSeconds;
    }

    public void setScanIntervalSeconds(int v) {
        this.scanIntervalSeconds = v;
    }
}
