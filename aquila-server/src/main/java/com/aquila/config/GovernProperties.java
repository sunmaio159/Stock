package com.aquila.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 治理队列参数（源：04 §7 govern）。
 */
@ConfigurationProperties(prefix = "aquila.govern")
public class GovernProperties {
    private int queue = 10000;
    private int offerTimeoutMs = 3000;
    private int simhashBand = 4;
    private int simhashHamming = 3;
    private int simhashWindowH = 24;

    public int getQueue() {
        return queue;
    }

    public void setQueue(int queue) {
        this.queue = queue;
    }

    public int getOfferTimeoutMs() {
        return offerTimeoutMs;
    }

    public void setOfferTimeoutMs(int v) {
        this.offerTimeoutMs = v;
    }

    public int getSimhashBand() {
        return simhashBand;
    }

    public void setSimhashBand(int v) {
        this.simhashBand = v;
    }

    public int getSimhashHamming() {
        return simhashHamming;
    }

    public void setSimhashHamming(int v) {
        this.simhashHamming = v;
    }

    public int getSimhashWindowH() {
        return simhashWindowH;
    }

    public void setSimhashWindowH(int v) {
        this.simhashWindowH = v;
    }
}
