package com.hyuk.flow_control.adapter.kafka;

import com.hyuk.flow_control.domain.retry.RetryStage;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;

import java.util.ArrayList;
import java.util.List;

public final class RequestTopicDefinitions {
    private static final int DEAD_LETTER_PARTITIONS = 1;
    private static final int EXPIRED_PARTITIONS = 1;
    private static final int RESULT_PARTITIONS = 1;

    private RequestTopicDefinitions() {
    }

    public static List<NewTopic> forTarget(String targetSystemId, int partitions, int replicas) {
        List<NewTopic> topics = new ArrayList<>();

        for (RetryStage stage : RetryStage.values()) {
            topics.add(topic(RequestTopics.retry(stage, targetSystemId), partitions, replicas));
        }
        topics.add(topic(RequestTopics.deadLetter(targetSystemId), DEAD_LETTER_PARTITIONS, replicas));
        topics.add(topic(RequestTopics.expired(targetSystemId), EXPIRED_PARTITIONS, replicas));

        return topics;
    }

    public static NewTopic request(String targetSystemId, String requesterId, int partitions, int replicas) {
        return topic(RequestTopics.of(targetSystemId, requesterId), partitions, replicas);
    }

    public static NewTopic result(String requesterId, int replicas) {
        return topic(RequestTopics.result(requesterId), RESULT_PARTITIONS, replicas);
    }

    private static NewTopic topic(String name, int partitions, int replicas) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
    }
}
