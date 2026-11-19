package com.payflow.api.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${app.kafka.topics.payment-events:payment-events}")
    private String paymentEventsTopic;

    @Value("${app.kafka.topics.payment-retry:payment-retry}")
    private String paymentRetryTopic;

    @Value("${app.kafka.topics.payment-dlq:payment-dlq}")
    private String paymentDlqTopic;

    @Value("${app.kafka.topics.fraud-alerts:fraud-alerts}")
    private String fraudAlertsTopic;

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(paymentEventsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentRetryTopic() {
        return TopicBuilder.name(paymentRetryTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic paymentDlqTopic() {
        return TopicBuilder.name(paymentDlqTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic fraudAlertsTopic() {
        return TopicBuilder.name(fraudAlertsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
