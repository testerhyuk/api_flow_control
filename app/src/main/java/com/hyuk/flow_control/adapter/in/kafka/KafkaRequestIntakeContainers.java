package com.hyuk.flow_control.adapter.in.kafka;

import com.hyuk.flow_control.adapter.kafka.RequestTopicDefinitions;
import com.hyuk.flow_control.adapter.kafka.RequestTopics;
import com.hyuk.flow_control.application.port.in.RequestHandlingUseCase;
import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.RequestIntakeControl;
import com.hyuk.flow_control.application.port.out.RequesterChangeListener;
import com.hyuk.flow_control.application.port.in.PolicyQuery;
import com.hyuk.flow_control.application.port.in.RequesterQuery;
import com.hyuk.flow_control.config.IntakeProperties;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.requester.Requester;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class KafkaRequestIntakeContainers
        implements SmartLifecycle, PolicyChangeListener, RequesterChangeListener, RequestIntakeControl {
    private static final Logger log = LoggerFactory.getLogger(KafkaRequestIntakeContainers.class);

    static final Duration ERROR_BACKOFF = Duration.ofSeconds(1);

    private final ConsumerFactory<String, String> consumerFactory;
    private final RequestHandlingUseCase handling;
    private final JsonMapper jsonMapper;
    private final KafkaAdmin kafkaAdmin;
    private final PolicyQuery policies;
    private final RequesterQuery requesters;
    private final MeterRegistry meterRegistry;
    private final String requestGroupId;
    private final boolean autoStartup;
    private final int topicReplicas;
    private final Duration retryMaxPollInterval;
    private final Map<String, TargetContainers> targets = new ConcurrentHashMap<>();
    private volatile boolean running;

    public KafkaRequestIntakeContainers(
            ConsumerFactory<String, String> consumerFactory,
            PolicyQuery policies,
            RequesterQuery requesters,
            RequestHandlingUseCase handling,
            JsonMapper jsonMapper,
            KafkaAdmin kafkaAdmin,
            MeterRegistry meterRegistry,
            IntakeProperties intakeProperties,
            @Value("${spring.kafka.consumer.group-id}") String requestGroupId,
            @Value("${flow-control.intake.auto-startup:true}") boolean autoStartup) {
        this.consumerFactory = consumerFactory;
        this.handling = handling;
        this.jsonMapper = jsonMapper;
        this.kafkaAdmin = kafkaAdmin;
        this.policies = policies;
        this.requesters = requesters;
        this.meterRegistry = meterRegistry;
        this.requestGroupId = requestGroupId;
        this.autoStartup = autoStartup;
        this.topicReplicas = intakeProperties.topicReplicas();
        this.retryMaxPollInterval = RetryStage.longestDelay().plus(intakeProperties.retryProcessingMargin());

        policies.policies().forEach(this::register);
    }

    @Override
    public synchronized void onPolicyAdded(ManagedPolicy policy) {
        String targetSystemId = policy.targetSystemId();
        if (targets.containsKey(targetSystemId)) {
            return;
        }

        createTopics(targetSystemId, policy.limits().concurrencyLimit());

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

        growIfNeeded(after, target);
    }

    @Override
    public synchronized void onRequesterChanged(Requester before, Requester after) {
        createTopics(after);

        Set<String> affected = new LinkedHashSet<>(after.targetSystemIds());
        if (before != null) {
            affected.addAll(before.targetSystemIds());
        }

        affected.forEach(targetSystemId -> {
            TargetContainers target = targets.get(targetSystemId);
            if (target != null) {
                refreshRequestContainer(targetSystemId, target);
            }
        });
    }

    public Map<String, Integer> concurrencyByTopic() {
        Map<String, Integer> result = new HashMap<>();
        targets.values().forEach(target -> target.all().forEach(container -> {
            for (String topic : container.getContainerProperties().getTopics()) {
                result.put(topic, container.getConcurrency());
            }
        }));
        return Map.copyOf(result);
    }

    @Override
    public void pause(String targetSystemId) {
        TargetContainers target = targets.get(targetSystemId);
        if (target != null) {
            target.pause();
        }
    }

    @Override
    public void resume(String targetSystemId) {
        TargetContainers target = targets.get(targetSystemId);
        if (target != null) {
            target.resume();
        }
    }

    public boolean isPaused(String targetSystemId) {
        TargetContainers target = targets.get(targetSystemId);
        return target != null && target.all().stream().allMatch(ConcurrentMessageListenerContainer::isPauseRequested);
    }

    public boolean isConsuming(String targetSystemId) {
        TargetContainers target = targets.get(targetSystemId);
        return target != null && target.all().stream().allMatch(ConcurrentMessageListenerContainer::isRunning);
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

        for (RetryStage stage : RetryStage.values()) {
            containers.add(retryContainer(targetSystemId, stage, concurrency));
        }

        TargetContainers target = new TargetContainers(List.copyOf(containers), concurrency, policy.enabled());
        List<String> requestTopics = requestTopicsOf(targetSystemId);
        target.requestTopics = requestTopics;
        target.request = requestContainer(targetSystemId, requestTopics, concurrency);
        targets.put(targetSystemId, target);
        registerGauges(targetSystemId, target);
        return target;
    }

    private ConcurrentMessageListenerContainer<String, String> retryContainer(
            String targetSystemId, RetryStage stage, int concurrency) {
        String topic = RequestTopics.retry(stage, targetSystemId);
        Properties retryConsumer = new Properties();
        retryConsumer.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                String.valueOf(retryMaxPollInterval.toMillis()));

        return container(topic, topic, concurrency,
                new KafkaRequestListener(targetSystemId, stage, handling, jsonMapper), retryConsumer);
    }

    private void createTopics(String targetSystemId, int partitions) {
        List<NewTopic> topics = new ArrayList<>(RequestTopicDefinitions.forTarget(targetSystemId, partitions,
                topicReplicas));
        requesters.requesterIdsOf(targetSystemId).forEach(requesterId ->
                topics.add(RequestTopicDefinitions.request(targetSystemId, requesterId, partitions, topicReplicas)));
        kafkaAdmin.createOrModifyTopics(topics.toArray(NewTopic[]::new));
    }

    private void growIfNeeded(ManagedPolicy policy, TargetContainers target) {
        String targetSystemId = policy.targetSystemId();
        int required = policy.limits().concurrencyLimit();
        if (required <= target.concurrency) {
            return;
        }

        createTopics(targetSystemId, required);
        int previous = target.concurrency;
        target.concurrency = required;

        RetryStage[] stages = RetryStage.values();
        for (int i = 0; i < stages.length; i++) {
            replace(target, target.retries.get(i), target.withRetry(i, retryContainer(targetSystemId, stages[i], required)));
        }
        ConcurrentMessageListenerContainer<String, String> request = target.request;
        target.request = requestContainer(targetSystemId, target.requestTopics, required);
        replace(target, request, target.request);

        log.info("읽기 스레드 수를 늘려 요청 읽기를 다시 띄웁니다 : {} ({} -> {})", targetSystemId, previous, required);
    }

    private void replace(TargetContainers target,
                         ConcurrentMessageListenerContainer<String, String> previous,
                         ConcurrentMessageListenerContainer<String, String> next) {
        if (previous != null && previous.isRunning()) {
            previous.stop(() -> startReplacement(target, next));
        } else {
            startReplacement(target, next);
        }
    }

    private void createTopics(Requester requester) {
        List<NewTopic> topics = new ArrayList<>();
        topics.add(RequestTopicDefinitions.result(requester.requesterId(), topicReplicas));
        requester.targetSystemIds().stream()
                .filter(targets::containsKey)
                .forEach(targetSystemId -> topics.add(RequestTopicDefinitions.request(targetSystemId,
                        requester.requesterId(), policies.read(targetSystemId).concurrencyLimit(), topicReplicas)));
        kafkaAdmin.createOrModifyTopics(topics.toArray(NewTopic[]::new));
    }

    private List<String> requestTopicsOf(String targetSystemId) {
        return requesters.enabledRequesterIdsOf(targetSystemId).stream()
                .map(requesterId -> RequestTopics.of(targetSystemId, requesterId))
                .toList();
    }

    private ConcurrentMessageListenerContainer<String, String> requestContainer(
            String targetSystemId, List<String> topics, int concurrency) {
        if (topics.isEmpty()) {
            return null;
        }
        return container(topics.toArray(String[]::new), requestGroupId, concurrency,
                new KafkaRequestListener(targetSystemId, null, handling, jsonMapper), null);
    }

    private void refreshRequestContainer(String targetSystemId, TargetContainers target) {
        List<String> topics = requestTopicsOf(targetSystemId);
        if (topics.equals(target.requestTopics)) {
            return;
        }

        ConcurrentMessageListenerContainer<String, String> previous = target.request;
        ConcurrentMessageListenerContainer<String, String> next =
                requestContainer(targetSystemId, topics, target.concurrency);
        target.requestTopics = topics;
        target.request = next;
        log.info("요청 토픽 읽기를 다시 띄웁니다 : {} {}", targetSystemId, topics);

        replace(target, previous, next);
    }

    private synchronized void startReplacement(TargetContainers target,
                                               ConcurrentMessageListenerContainer<String, String> next) {
        if (next != null && target.all().contains(next) && running && target.enabled) {
            target.startOne(next);
        }
    }

    private void registerGauges(String targetSystemId, TargetContainers target) {
        Gauge.builder("flow.control.intake.consumers", () -> target.concurrency)
                .tag("target", targetSystemId)
                .register(meterRegistry);
    }

    private ConcurrentMessageListenerContainer<String, String> container(
            String topic, String groupId, int concurrency, KafkaRequestListener listener, Properties consumerOverrides) {
        return container(new String[]{topic}, groupId, concurrency, listener, consumerOverrides);
    }

    private ConcurrentMessageListenerContainer<String, String> container(
            String[] topics, String groupId, int concurrency, KafkaRequestListener listener,
            Properties consumerOverrides) {
        ContainerProperties properties = new ContainerProperties(topics);
        properties.setGroupId(groupId);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMessageListener(listener);
        if (consumerOverrides != null) {
            properties.setKafkaConsumerProperties(consumerOverrides);
        }

        ConcurrentMessageListenerContainer<String, String> container =
                new ConcurrentMessageListenerContainer<>(consumerFactory, properties);
        container.setConcurrency(concurrency);
        container.setBeanName("intake-" + String.join(",", topics));
        container.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(ERROR_BACKOFF.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS)));

        return container;
    }

    private static final class TargetContainers {
        private volatile List<ConcurrentMessageListenerContainer<String, String>> retries;
        private volatile int concurrency;
        private volatile ConcurrentMessageListenerContainer<String, String> request;
        private volatile List<String> requestTopics = List.of();
        private volatile boolean enabled;
        private volatile boolean paused;

        private TargetContainers(List<ConcurrentMessageListenerContainer<String, String>> retries,
                                 int concurrency, boolean enabled) {
            this.retries = retries;
            this.concurrency = concurrency;
            this.enabled = enabled;
        }

        private ConcurrentMessageListenerContainer<String, String> withRetry(
                int index, ConcurrentMessageListenerContainer<String, String> next) {
            List<ConcurrentMessageListenerContainer<String, String>> replaced = new ArrayList<>(retries);
            replaced.set(index, next);
            retries = List.copyOf(replaced);
            return next;
        }

        private List<ConcurrentMessageListenerContainer<String, String>> all() {
            List<ConcurrentMessageListenerContainer<String, String>> all = new ArrayList<>(retries);
            ConcurrentMessageListenerContainer<String, String> current = request;
            if (current != null) {
                all.add(current);
            }
            return all;
        }

        private void start() {
            retries.forEach(this::startOne);
            startRequest();
        }

        private void startRequest() {
            ConcurrentMessageListenerContainer<String, String> current = request;
            if (current != null) {
                startOne(current);
            }
        }

        private void startOne(ConcurrentMessageListenerContainer<String, String> container) {
            if (container.isRunning()) {
                return;
            }
            if (paused && !container.isPauseRequested()) {
                container.pause();
            }
            container.start();
        }

        private void stop() {
            all().forEach(container -> {
                if (container.isRunning()) {
                    container.stop();
                }
            });
        }

        private void pause() {
            paused = true;
            all().forEach(container -> {
                if (!container.isPauseRequested()) {
                    container.pause();
                }
            });
        }

        private void resume() {
            paused = false;
            all().forEach(container -> {
                if (container.isPauseRequested()) {
                    container.resume();
                }
            });
        }

        private void stopInBackground() {
            all().forEach(container -> {
                if (container.isRunning()) {
                    container.stop(() -> { });
                }
            });
        }
    }
}
