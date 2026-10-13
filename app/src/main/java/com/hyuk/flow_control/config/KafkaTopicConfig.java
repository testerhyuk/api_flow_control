package com.hyuk.flow_control.config;

import com.hyuk.flow_control.adapter.in.kafka.RequestTopics;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public KafkaAdmin.NewTopics requestTopics(TargetSystemPolicyReader policyReader) {
        NewTopic[] topics = policyReader.readAll().entrySet().stream()
                .map(entry -> TopicBuilder.name(RequestTopics.of(entry.getKey()))
                        .partitions(entry.getValue().concurrencyLimit())
                        .replicas(1)
                        .build())
                .toArray(NewTopic[]::new);

        return new KafkaAdmin.NewTopics(topics);
    }
}
