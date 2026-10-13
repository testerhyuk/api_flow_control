package com.hyuk.record_ingestor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class RequestGenerator implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(RequestGenerator.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RequestMessageFactory messageFactory;
    private final JsonMapper jsonMapper;
    private final IngestorProperties properties;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public RequestGenerator(
            KafkaTemplate<String, String> kafkaTemplate,
            RequestMessageFactory messageFactory,
            JsonMapper jsonMapper,
            IngestorProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.messageFactory = messageFactory;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        scheduler = Executors.newScheduledThreadPool(properties.targets().size());

        properties.targets().forEach((targetSystemId, target) -> {
            long periodNanos = periodNanosOf(target.rate());
            scheduler.scheduleAtFixedRate(() -> sendOne(targetSystemId), 0, periodNanos, TimeUnit.NANOSECONDS);
            log.info("요청 생성 시작 : {} 초당 {}건", targetSystemId, target.rate());
        });

        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }

        scheduler.shutdownNow();
        running = false;
        log.info("요청 생성 중지");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    static long periodNanosOf(double rate) {
        return Math.max(1, Math.round(1_000_000_000.0 / rate));
    }

    private void sendOne(String targetSystemId) {
        try {
            RequestMessage message = messageFactory.create(targetSystemId);
            String topic = properties.topicPrefix() + targetSystemId;

            kafkaTemplate.send(topic, message.requestId(), jsonMapper.writeValueAsString(message))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("요청 메시지 전송 실패 : {}", topic, error);
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("요청 메시지 생성 실패 : {}", targetSystemId, e);
        }
    }
}
