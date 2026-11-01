package com.hyuk.flow_control.config;

import com.hyuk.flow_control.adapter.kafka.RequestTopicDefinitions;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.service.RequesterRegistry;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public KafkaAdmin.NewTopics requestTopics(TargetSystemPolicyReader policyReader, RequesterRegistry requesters,
                                              IntakeProperties intakeProperties) {
        int replicas = intakeProperties.topicReplicas();
        Map<String, TargetSystemPolicy> policies = policyReader.readAll();
        List<NewTopic> topics = new ArrayList<>();

        policies.forEach((targetSystemId, policy) ->
                topics.addAll(RequestTopicDefinitions.forTarget(targetSystemId, policy.concurrencyLimit(), replicas)));

        requesters.requesters().forEach(requester -> {
            topics.add(RequestTopicDefinitions.result(requester.requesterId(), replicas));
            requester.targetSystemIds().stream()
                    .filter(policies::containsKey)
                    .forEach(targetSystemId -> topics.add(RequestTopicDefinitions.request(targetSystemId,
                            requester.requesterId(), policies.get(targetSystemId).concurrencyLimit(), replicas)));
        });

        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
}
