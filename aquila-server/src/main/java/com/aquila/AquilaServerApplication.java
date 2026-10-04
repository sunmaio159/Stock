package com.aquila;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;

/**
 * Aquila 投研工具 V1 后端启动类。
 * 依据：落地文档设计/02（架构）+ 03（契约）+ 04（实现）+ 06（W0 工程奠基）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.aquila")
@EnableScheduling
public class AquilaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AquilaServerApplication.class, args);
    }

    /** 全系统统一 Asia/Shanghai（核心口径 3：禁用 MON-FRI / minusDays(1) 判交易日）。 */
    @PostConstruct
    void setDefaultZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }
}
