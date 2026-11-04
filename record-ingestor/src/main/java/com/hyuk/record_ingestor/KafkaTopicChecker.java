package com.hyuk.record_ingestor;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaTopicChecker implements TopicChecker {
    private static final int TIMEOUT_MILLIS = 5000;

    private final Map<String, Object> adminConfig;

    public KafkaTopicChecker(KafkaAdmin kafkaAdmin) {
        this.adminConfig = kafkaAdmin.getConfigurationProperties();
    }

    @Override
    public void requireExisting(Collection<String> topics) {
        Set<String> existing = listTopics();
        List<String> missing = topics.stream().filter(topic -> !existing.contains(topic)).toList();

        if (!missing.isEmpty()) {
            throw new IllegalStateException("토픽이 없습니다 : " + missing
                    + ". flow-control 이 아직 토픽을 만들지 않았거나, 요청자나 외부 서버가 등록되지 않았습니다");
        }
    }

    private Set<String> listTopics() {
        try (AdminClient client = AdminClient.create(adminConfig)) {
            return client.listTopics(new ListTopicsOptions().timeoutMs(TIMEOUT_MILLIS))
                    .names()
                    .get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka 를 확인하지 못했습니다 : " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("토픽을 확인하는 중에 중단되었습니다", e);
        }
    }
}
