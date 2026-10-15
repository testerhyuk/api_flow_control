package com.hyuk.flow_control.config;

import com.hyuk.flow_control.adapter.kafka.RequestTopicDefinitions;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public KafkaAdmin.NewTopics requestTopics(TargetSystemPolicyReader policyReader, IntakeProperties intakeProperties) {
        List<NewTopic> topics = new ArrayList<>();

        policyReader.readAll().forEach((targetSystemId, policy) ->
                topics.addAll(RequestTopicDefinitions.forTarget(targetSystemId, policy.concurrencyLimit(),
                        intakeProperties.topicReplicas())));

        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
}
