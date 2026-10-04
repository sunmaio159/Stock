package com.aquila.config;

import com.aquila.pipeline.BackpressureQueue;
import com.aquila.pipeline.CrawlExecPool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 流水线线程池/队列装配（W0.5）。参数取自 aquila.crawl / aquila.govern 配置基线。
 */
@Configuration
public class PipelineConfig {

    /** 采集执行池：core=max=execPool, 有界队列 execQueue, AbortPolicy（F-CRAWL-04）。 */
    @Bean(destroyMethod = "shutdown")
    public CrawlExecPool crawlExecPool(CrawlProperties props) {
        return new CrawlExecPool(props.getExecPool(), props.getExecQueue());
    }

    /** 治理背压队列：容量 govern.queue，offer 超时 govern.offerTimeoutMs。 */
    @Bean(name = "governQueue")
    public BackpressureQueue<Object> governQueue(GovernProperties props) {
        return new BackpressureQueue<>(props.getQueue(), props.getOfferTimeoutMs());
    }
}
