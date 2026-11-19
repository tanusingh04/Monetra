package com.payflow.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.payflow.core", "com.payflow.api"})
@EntityScan(basePackages = "com.payflow.core.domain")
@EnableJpaRepositories(basePackages = "com.payflow.core.repository")
@EnableScheduling
public class PayFlowApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayFlowApiApplication.class, args);
    }
}
