package com.hyuk.flow_control.adapter.out.kafka;

import com.hyuk.flow_control.adapter.kafka.RequestTopics;
import com.hyuk.flow_control.adapter.kafka.ResultMessage;
import com.hyuk.flow_control.application.port.out.ResultPublisher;
import com.hyuk.flow_control.domain.result.RequestResult;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

@Component
public class KafkaResultPublisher implements ResultPublisher {
    private final RetryingKafkaSender sender;
    private final JsonMapper jsonMapper;

    @Autowired
    public KafkaResultPublisher(KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper) {
        this(kafkaTemplate, jsonMapper, RetryingKafkaSender.RETRY_INTERVAL);
    }

    KafkaResultPublisher(KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper, Duration retryInterval) {
        this.sender = new RetryingKafkaSender(kafkaTemplate, retryInterval);
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void publish(String requesterId, RequestResult result) {
        sender.send(new ProducerRecord<>(
                RequestTopics.result(requesterId),
                result.requestId(),
                jsonMapper.writeValueAsString(ResultMessage.from(result))));
    }
}
