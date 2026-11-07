package com.hyuk.integration_test.support;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class Kafka {
    private static final String BOOTSTRAP_SERVERS = "localhost:9092,localhost:9094,localhost:9096";

    private Kafka() {
    }

    public static Set<String> topicNames() {
        try (Admin admin = admin()) {
            return admin.listTopics().names().get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("토픽 목록을 읽지 못했습니다", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("토픽 목록을 읽는 중 중단되었습니다", e);
        }
    }

    /** 토픽에 쌓인 전체 건수. 파티션별 끝 오프셋을 더한다. */
    public static long messageCount(String topic) {
        try (Admin admin = admin()) {
            Set<TopicPartition> partitions = admin.describeTopics(Set.of(topic)).allTopicNames().get().get(topic)
                    .partitions().stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .collect(Collectors.toSet());
            Map<TopicPartition, OffsetSpec> latest = partitions.stream()
                    .collect(Collectors.toMap(Function.identity(), partition -> OffsetSpec.latest()));

            return admin.listOffsets(latest).all().get().values().stream()
                    .mapToLong(info -> info.offset())
                    .sum();
        } catch (ExecutionException e) {
            throw new IllegalStateException("토픽 건수를 읽지 못했습니다 : " + topic, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("토픽 건수를 읽는 중 중단되었습니다", e);
        }
    }

    /** 대상의 요청 토픽과 재시도 토픽에서 아직 처리하지 않은 건수. 모든 소비 그룹의 커밋 위치와 끝 오프셋의 차이를 더한다. */
    public static long pendingRequests(String target) {
        try (Admin admin = admin()) {
            long pending = 0;
            for (var group : admin.listGroups().all().get()) {
                Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group.groupId())
                        .partitionsToOffsetAndMetadata().get().entrySet().stream()
                        .filter(entry -> isPendingTopic(entry.getKey().topic(), target) && entry.getValue() != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                if (committed.isEmpty()) {
                    continue;
                }
                Map<TopicPartition, OffsetSpec> latest = committed.keySet().stream()
                        .collect(Collectors.toMap(Function.identity(), partition -> OffsetSpec.latest()));
                var ends = admin.listOffsets(latest).all().get();
                for (var entry : committed.entrySet()) {
                    pending += ends.get(entry.getKey()).offset() - entry.getValue().offset();
                }
            }
            return pending;
        } catch (ExecutionException e) {
            throw new IllegalStateException("처리하지 않은 건수를 읽지 못했습니다 : " + target, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("처리하지 않은 건수를 읽는 중 중단되었습니다", e);
        }
    }

    private static boolean isPendingTopic(String topic, String target) {
        return topic.startsWith("flow-control.requests." + target + ".")
                || (topic.startsWith("flow-control.retry-") && topic.endsWith("." + target));
    }

    private static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS));
    }
}
