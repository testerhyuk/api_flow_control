package com.hyuk.flow_control.adapter.in.kafka;

import com.hyuk.flow_control.adapter.kafka.RequestTopicDefinitions;
import com.hyuk.flow_control.adapter.kafka.RequestTopics;
import com.hyuk.flow_control.application.port.in.RequestHandlingUseCase;
import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.service.PolicyRegistry;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.retry.RetryStage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
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
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class KafkaRequestIntakeContainers implements SmartLifecycle, PolicyChangeListener {
    private static final Logger log = LoggerFactory.getLogger(KafkaRequestIntakeContainers.class);

    static final String REQUEST_GROUP_ID = "flow-control";
    static final Duration RETRY_MAX_POLL_INTERVAL = Duration.ofMinutes(15);
    static final Duration ERROR_BACKOFF = Duration.ofSeconds(1);

    private final ConsumerFactory<String, String> consumerFactory;
    private final RequestHandlingUseCase handling;
    private final JsonMapper jsonMapper;
    private final KafkaAdmin kafkaAdmin;
    private final PolicyRegistry policies;
    private final MeterRegistry meterRegistry;
    private final boolean autoStartup;
    private final Map<String, TargetContainers> targets = new ConcurrentHashMap<>();
    private final Map<String, Long> warnedVersions = new ConcurrentHashMap<>();
    private volatile boolean running;

    public KafkaRequestIntakeContainers(
            ConsumerFactory<String, String> consumerFactory,
            PolicyRegistry policies,
            RequestHandlingUseCase handling,
            JsonMapper jsonMapper,
            KafkaAdmin kafkaAdmin,
            MeterRegistry meterRegistry,
            @Value("${flow-control.intake.auto-startup:true}") boolean autoStartup) {
        this.consumerFactory = consumerFactory;
        this.handling = handling;
        this.jsonMapper = jsonMapper;
        this.kafkaAdmin = kafkaAdmin;
        this.policies = policies;
        this.meterRegistry = meterRegistry;
        this.autoStartup = autoStartup;

        policies.policies().forEach(this::register);
    }

    @Override
    public synchronized void onPolicyAdded(ManagedPolicy policy) {
        String targetSystemId = policy.targetSystemId();
        if (targets.containsKey(targetSystemId)) {
            return;
        }

        List<NewTopic> topics = RequestTopicDefinitions.forTarget(targetSystemId, policy.limits().concurrencyLimit());
        kafkaAdmin.createOrModifyTopics(topics.toArray(NewTopic[]::new));

        TargetContainers created = register(policy);
        if (running && created.enabled) {
            created.start();
        }
        log.info("대상 시스템 요청 읽기를 추가했습니다 : {} (읽기 스레드 {}, 사용 {})",
                targetSystemId, created.concurrency, created.enabled);
    }

    @Override
    public synchronized void onPolicyChanged(ManagedPolicy before, ManagedPolicy after) {
        TargetContainers target = targets.get(after.targetSystemId());
        if (target == null) {
            onPolicyAdded(after);
            return;
        }

        if (before.enabled() != after.enabled()) {
            target.enabled = after.enabled();
            if (after.enabled() && running) {
                target.start();
                log.info("대상 시스템 요청 읽기를 다시 시작했습니다 : {}", after.targetSystemId());
            } else if (!after.enabled()) {
                target.stopInBackground();
                log.info("대상 시스템 요청 읽기를 멈췄습니다 : {}", after.targetSystemId());
            }
        }

        warnIfRedeployRequired(after, target);
    }

    public Map<String, Integer> concurrencyByTopic() {
        Map<String, Integer> result = new HashMap<>();
        targets.values().forEach(target -> target.containers.forEach(container ->
                result.put(container.getContainerProperties().getTopics()[0], container.getConcurrency())));
        return Map.copyOf(result);
    }

    public boolean isConsuming(String targetSystemId) {
        TargetContainers target = targets.get(targetSystemId);
        return target != null && target.containers.stream().allMatch(ConcurrentMessageListenerContainer::isRunning);
    }

    @Override
    public synchronized void start() {
        targets.values().forEach(target -> {
            if (target.enabled) {
                target.start();
            }
        });
        running = true;
    }

    @Override
    public synchronized void stop() {
        targets.values().forEach(TargetContainers::stop);
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

    private TargetContainers register(ManagedPolicy policy) {
        String targetSystemId = policy.targetSystemId();
        int concurrency = policy.limits().concurrencyLimit();
        List<ConcurrentMessageListenerContainer<String, String>> containers = new ArrayList<>();

        containers.add(container(RequestTopics.of(targetSystemId), REQUEST_GROUP_ID, concurrency,
                new KafkaRequestListener(targetSystemId, null, handling, jsonMapper), null));

        for (RetryStage stage : RetryStage.values()) {
            String topic = RequestTopics.retry(stage, targetSystemId);
            Properties retryConsumer = new Properties();
            retryConsumer.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                    String.valueOf(RETRY_MAX_POLL_INTERVAL.toMillis()));

            containers.add(container(topic, topic, concurrency,
                    new KafkaRequestListener(targetSystemId, stage, handling, jsonMapper), retryConsumer));
        }

        TargetContainers target = new TargetContainers(List.copyOf(containers), concurrency, policy.enabled());
        targets.put(targetSystemId, target);
        registerGauges(targetSystemId, target);
        return target;
    }

    private void registerGauges(String targetSystemId, TargetContainers target) {
        Gauge.builder("flow.control.intake.consumers", () -> target.concurrency)
                .tag("target", targetSystemId)
                .register(meterRegistry);
        Gauge.builder("flow.control.intake.redeploy.required",
                        () -> policies.read(targetSystemId).concurrencyLimit() > target.concurrency ? 1 : 0)
                .tag("target", targetSystemId)
                .register(meterRegistry);
    }

    private void warnIfRedeployRequired(ManagedPolicy policy, TargetContainers target) {
        int required = policy.limits().concurrencyLimit();

        if (required <= target.concurrency) {
            warnedVersions.remove(policy.targetSystemId());
            return;
        }

        Long warned = warnedVersions.put(policy.targetSystemId(), policy.version());
        if (warned == null || warned != policy.version()) {
            log.warn("concurrency 가 읽기 스레드 수를 넘었습니다. 재배포해야 올린 한도가 모두 반영됩니다 : {} (필요 {}, 지금 {})",
                    policy.targetSystemId(), required, target.concurrency);
        }
    }

    private ConcurrentMessageListenerContainer<String, String> container(
            String topic, String groupId, int concurrency, KafkaRequestListener listener, Properties consumerOverrides) {
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

    private static final class TargetContainers {
        private final List<ConcurrentMessageListenerContainer<String, String>> containers;
        private final int concurrency;
        private volatile boolean enabled;

        private TargetContainers(List<ConcurrentMessageListenerContainer<String, String>> containers,
                                 int concurrency, boolean enabled) {
            this.containers = containers;
            this.concurrency = concurrency;
            this.enabled = enabled;
        }

        private void start() {
            containers.forEach(container -> {
                if (!container.isRunning()) {
                    container.start();
                }
            });
        }

        private void stop() {
            containers.forEach(container -> {
                if (container.isRunning()) {
                    container.stop();
                }
            });
        }

        private void stopInBackground() {
            containers.forEach(container -> {
                if (container.isRunning()) {
                    container.stop(() -> { });
                }
            });
        }
    }
}
