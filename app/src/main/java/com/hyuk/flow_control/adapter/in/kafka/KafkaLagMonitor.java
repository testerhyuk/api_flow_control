package com.hyuk.flow_control.adapter.in.kafka;

import com.hyuk.flow_control.adapter.kafka.RequestTopics;
import com.hyuk.flow_control.application.service.PolicyRegistry;
import com.hyuk.flow_control.config.IntakeProperties;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.retry.RetryStage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaLagMonitor implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(KafkaLagMonitor.class);

    static final String REQUEST_STAGE = "request";

    private final KafkaAdmin kafkaAdmin;
    private final PolicyRegistry policies;
    private final MeterRegistry meterRegistry;
    private final Duration interval;
    private final boolean autoStartup;
    private final Map<String, Double> lags = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService executor;
    private volatile AdminClient admin;
    private volatile boolean failing;

    public KafkaLagMonitor(
            KafkaAdmin kafkaAdmin,
            PolicyRegistry policies,
            MeterRegistry meterRegistry,
            IntakeProperties intakeProperties,
            @Value("${flow-control.intake.auto-startup:true}") boolean autoStartup) {
        this.kafkaAdmin = kafkaAdmin;
        this.policies = policies;
        this.meterRegistry = meterRegistry;
        this.interval = intakeProperties.lagInterval();
        this.autoStartup = autoStartup;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }

        admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kafka-lag-monitor");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::refreshSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor == null) {
            return;
        }

        executor.shutdownNow();
        executor = null;
        admin.close(Duration.ofSeconds(1));
        admin = null;
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStartup;
    }

    public double lagOf(String topic) {
        return lags.getOrDefault(topic, Double.NaN);
    }

    private void refreshSafely() {
        try {
            refresh();
            if (failing) {
                failing = false;
                log.info("컨슈머 랙을 다시 계산합니다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!failing) {
                failing = true;
                log.warn("컨슈머 랙을 계산하지 못했습니다. 다음 주기에 다시 시도합니다", e);
            }
            lags.replaceAll((topic, lag) -> Double.NaN);
        }
    }

    void refresh() throws Exception {
        Map<String, String> groupsByTopic = new LinkedHashMap<>();

        for (ManagedPolicy policy : policies.policies()) {
            String targetSystemId = policy.targetSystemId();
            register(targetSystemId, RequestTopics.of(targetSystemId), REQUEST_STAGE);
            groupsByTopic.put(RequestTopics.of(targetSystemId), KafkaRequestIntakeContainers.REQUEST_GROUP_ID);

            for (RetryStage stage : RetryStage.values()) {
                String topic = RequestTopics.retry(stage, targetSystemId);
                register(targetSystemId, topic, stage.label());
                groupsByTopic.put(topic, topic);
            }
        }

        if (groupsByTopic.isEmpty()) {
            return;
        }

        long timeout = interval.toMillis();
        Map<String, TopicDescription> descriptions = admin.describeTopics(groupsByTopic.keySet())
                .allTopicNames().get(timeout, TimeUnit.MILLISECONDS);

        Map<String, List<TopicPartition>> partitionsByTopic = new HashMap<>();
        List<TopicPartition> all = new ArrayList<>();
        descriptions.forEach((topic, description) -> description.partitions().forEach(info -> {
            TopicPartition partition = new TopicPartition(topic, info.partition());
            partitionsByTopic.computeIfAbsent(topic, key -> new ArrayList<>()).add(partition);
            all.add(partition);
        }));

        Map<TopicPartition, Long> latest = offsets(all, OffsetSpec.latest(), timeout);
        Map<TopicPartition, Long> earliest = offsets(all, OffsetSpec.earliest(), timeout);

        Map<String, ListConsumerGroupOffsetsSpec> specs = new HashMap<>();
        groupsByTopic.forEach((topic, group) -> {
            List<TopicPartition> partitions = partitionsByTopic.getOrDefault(topic, List.of());
            specs.merge(group, new ListConsumerGroupOffsetsSpec().topicPartitions(partitions), (left, right) -> {
                List<TopicPartition> merged = new ArrayList<>(left.topicPartitions());
                merged.addAll(right.topicPartitions());
                return new ListConsumerGroupOffsetsSpec().topicPartitions(merged);
            });
        });

        Map<String, Map<TopicPartition, OffsetAndMetadata>> committed =
                admin.listConsumerGroupOffsets(specs).all().get(timeout, TimeUnit.MILLISECONDS);

        groupsByTopic.forEach((topic, group) -> {
            Map<TopicPartition, OffsetAndMetadata> offsets = committed.getOrDefault(group, Map.of());
            long lag = 0;

            for (TopicPartition partition : partitionsByTopic.getOrDefault(topic, List.of())) {
                OffsetAndMetadata position = offsets.get(partition);
                long consumed = position == null ? earliest.getOrDefault(partition, 0L) : position.offset();
                lag += Math.max(0, latest.getOrDefault(partition, 0L) - consumed);
            }

            lags.put(topic, (double) lag);
        });
    }

    private Map<TopicPartition, Long> offsets(List<TopicPartition> partitions, OffsetSpec spec, long timeout)
            throws Exception {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        partitions.forEach(partition -> request.put(partition, spec));

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> result =
                admin.listOffsets(request).all().get(timeout, TimeUnit.MILLISECONDS);

        Map<TopicPartition, Long> offsets = new HashMap<>();
        result.forEach((partition, info) -> offsets.put(partition, info.offset()));
        return offsets;
    }

    private void register(String targetSystemId, String topic, String stage) {
        if (lags.putIfAbsent(topic, Double.NaN) != null) {
            return;
        }

        Gauge.builder("flow.control.intake.lag", () -> lagOf(topic))
                .tag("target", targetSystemId)
                .tag("stage", stage)
                .register(meterRegistry);
    }
}
