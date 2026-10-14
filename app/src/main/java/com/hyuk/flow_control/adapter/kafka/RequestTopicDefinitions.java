package com.hyuk.flow_control.adapter.kafka;

import com.hyuk.flow_control.domain.retry.RetryStage;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;

import java.util.ArrayList;
import java.util.List;

public final class RequestTopicDefinitions {
    private static final int DEAD_LETTER_PARTITIONS = 1;
    private static final int REPLICAS = 1;

    private RequestTopicDefinitions() {
    }

    public static List<NewTopic> forTarget(String targetSystemId, int partitions) {
        List<NewTopic> topics = new ArrayList<>();

        topics.add(topic(RequestTopics.of(targetSystemId), partitions));
        for (RetryStage stage : RetryStage.values()) {
            topics.add(topic(RequestTopics.retry(stage, targetSystemId), partitions));
        }
        topics.add(topic(RequestTopics.deadLetter(targetSystemId), DEAD_LETTER_PARTITIONS));

        return topics;
    }

    private static NewTopic topic(String name, int partitions) {
        return TopicBuilder.name(name).partitions(partitions).replicas(REPLICAS).build();
    }
}
