package com.aquila;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.WebApplicationType;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class AquilaServerApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AquilaServerApplication.class);
        java.util.List<String> arguments = java.util.Arrays.asList(args);
        if (arguments.contains("--local-admin-create") || arguments.contains("--local-admin-reset")) {
            if (!"true".equalsIgnoreCase(System.getenv("STOCK_DB_ENABLED"))) {
                System.err.println("Local administrator setup requires the explicit greenfield database configuration.");
                return;
            }
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.run(args);
    }
}
