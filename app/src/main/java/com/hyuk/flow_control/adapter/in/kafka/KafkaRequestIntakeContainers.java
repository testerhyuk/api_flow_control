package com.hyuk.flow_control.adapter.in.kafka;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class KafkaRequestIntakeContainers implements SmartLifecycle {
    private final List<ConcurrentMessageListenerContainer<String, String>> containers;
    private final boolean autoStartup;
    private volatile boolean running;

    public KafkaRequestIntakeContainers(
            ConsumerFactory<String, String> consumerFactory,
            TargetSystemPolicyReader policyReader,
            KafkaRequestListener listener,
            @Value("${flow-control.intake.auto-startup:true}") boolean autoStartup) {
        List<ConcurrentMessageListenerContainer<String, String>> created = new ArrayList<>();

        policyReader.readAll().forEach((targetSystemId, policy) -> {
            ContainerProperties properties = new ContainerProperties(RequestTopics.of(targetSystemId));
            properties.setAckMode(ContainerProperties.AckMode.RECORD);
            properties.setMessageListener(listener);

            ConcurrentMessageListenerContainer<String, String> container =
                    new ConcurrentMessageListenerContainer<>(consumerFactory, properties);
            container.setConcurrency(policy.concurrencyLimit());
            container.setBeanName("request-intake-" + targetSystemId);

            created.add(container);
        });

        this.containers = List.copyOf(created);
        this.autoStartup = autoStartup;
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
