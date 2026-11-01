package com.hyuk.flow_control.adapter.out.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class RetryingKafkaSender {
    private static final Logger log = LoggerFactory.getLogger(RetryingKafkaSender.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;
    static final Duration RETRY_INTERVAL = Duration.ofSeconds(1);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration retryInterval;

    RetryingKafkaSender(KafkaTemplate<String, String> kafkaTemplate, Duration retryInterval) {
        this.kafkaTemplate = kafkaTemplate;
        this.retryInterval = retryInterval;
    }

    void send(ProducerRecord<String, String> record) {
        int failures = 0;

        while (true) {
            try {
                kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return;
            } catch (InterruptedException e) {
                throw interrupted(record, e);
            } catch (ExecutionException | TimeoutException | KafkaException e) {
                failures++;
                log.warn("토픽에 넣지 못해 {} 뒤 다시 넣습니다 : {} {}번째 실패", retryInterval, record.topic(), failures, e);
            }

            try {
                Thread.sleep(retryInterval);
            } catch (InterruptedException e) {
                throw interrupted(record, e);
            }
        }
    }

    private static IllegalStateException interrupted(ProducerRecord<String, String> record, InterruptedException e) {
        Thread.currentThread().interrupt();
        return new IllegalStateException("토픽에 넣는 중 중단되었습니다 : " + record.topic(), e);
    }
}
