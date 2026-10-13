package com.hyuk.flow_control.adapter.in.kafka;

import com.hyuk.flow_control.adapter.kafka.RequestTopics;
import com.hyuk.flow_control.application.port.in.RequestHandlingUseCase;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.domain.retry.RetryStage;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Component
public class KafkaRequestIntakeContainers implements SmartLifecycle {
    static final String REQUEST_GROUP_ID = "flow-control";
    static final Duration RETRY_MAX_POLL_INTERVAL = Duration.ofMinutes(15);
    static final Duration ERROR_BACKOFF = Duration.ofSeconds(1);

    private final List<ConcurrentMessageListenerContainer<String, String>> containers;
    private final boolean autoStartup;
    private volatile boolean running;

    public KafkaRequestIntakeContainers(
            ConsumerFactory<String, String> consumerFactory,
            TargetSystemPolicyReader policyReader,
            RequestHandlingUseCase handling,
            JsonMapper jsonMapper,
            @Value("${flow-control.intake.auto-startup:true}") boolean autoStartup) {
        List<ConcurrentMessageListenerContainer<String, String>> created = new ArrayList<>();

        policyReader.readAll().forEach((targetSystemId, policy) -> {
            int concurrency = policy.concurrencyLimit();

            created.add(container(consumerFactory, RequestTopics.of(targetSystemId), REQUEST_GROUP_ID, concurrency,
                    new KafkaRequestListener(targetSystemId, null, handling, jsonMapper), null));

            for (RetryStage stage : RetryStage.values()) {
                String topic = RequestTopics.retry(stage, targetSystemId);
                Properties retryConsumer = new Properties();
                retryConsumer.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                        String.valueOf(RETRY_MAX_POLL_INTERVAL.toMillis()));

                created.add(container(consumerFactory, topic, topic, concurrency,
                        new KafkaRequestListener(targetSystemId, stage, handling, jsonMapper), retryConsumer));
            }
        });

        this.containers = List.copyOf(created);
        this.autoStartup = autoStartup;
    }

    private static ConcurrentMessageListenerContainer<String, String> container(
            ConsumerFactory<String, String> consumerFactory, String topic, String groupId, int concurrency,
            KafkaRequestListener listener, Properties consumerOverrides) {
        ContainerProperties properties = new ContainerProperties(topic);
        properties.setGroupId(groupId);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMessageListener(listener);
        if (consumerOverrides != null) {
            properties.setKafkaConsumerProperties(consumerOverrides);
        }

        ConcurrentMessageListenerContainer<String, String> container =
                new ConcurrentMessageListenerContainer<>(consumerFactory, properties);
        container.setConcurrency(concurrency);
        container.setBeanName("intake-" + topic);
        container.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(ERROR_BACKOFF.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS)));

        return container;
    }

    public Map<String, Integer> concurrencyByTopic() {
        Map<String, Integer> result = new HashMap<>();
        containers.forEach(container ->
                result.put(container.getContainerProperties().getTopics()[0], container.getConcurrency()));
        return Map.copyOf(result);
    }

    @Override
    public void start() {
        containers.forEach(ConcurrentMessageListenerContainer::start);
        running = true;
    }

    @Override
    public void stop() {
        containers.forEach(ConcurrentMessageListenerContainer::stop);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStartup;
    }
}
