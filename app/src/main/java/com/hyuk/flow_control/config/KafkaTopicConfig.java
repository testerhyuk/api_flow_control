package com.hyuk.flow_control.config;

import com.hyuk.flow_control.adapter.kafka.RequestTopics;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.domain.retry.RetryStage;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class KafkaTopicConfig {
    private static final int DEAD_LETTER_PARTITIONS = 1;
    private static final int REPLICAS = 1;

    @Bean
    public KafkaAdmin.NewTopics requestTopics(TargetSystemPolicyReader policyReader) {
        List<NewTopic> topics = new ArrayList<>();

        policyReader.readAll().forEach((targetSystemId, policy) -> {
            int partitions = policy.concurrencyLimit();

            topics.add(topic(RequestTopics.of(targetSystemId), partitions));
            for (RetryStage stage : RetryStage.values()) {
                topics.add(topic(RequestTopics.retry(stage, targetSystemId), partitions));
            }
            topics.add(topic(RequestTopics.deadLetter(targetSystemId), DEAD_LETTER_PARTITIONS));
        });

        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    private static NewTopic topic(String name, int partitions) {
        return TopicBuilder.name(name).partitions(partitions).replicas(REPLICAS).build();
    }
}
