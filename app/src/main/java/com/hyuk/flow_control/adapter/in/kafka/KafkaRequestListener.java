package com.hyuk.flow_control.adapter.in.kafka;

import com.hyuk.flow_control.application.port.in.RequestIntakeUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class KafkaRequestListener implements MessageListener<String, String> {
    private static final Logger log = LoggerFactory.getLogger(KafkaRequestListener.class);

    private final RequestIntakeUseCase intake;
    private final JsonMapper jsonMapper;

    public KafkaRequestListener(RequestIntakeUseCase intake, JsonMapper jsonMapper) {
        this.intake = intake;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void onMessage(ConsumerRecord<String, String> record) {
        RequestMessage message = parse(record);

        if (message == null) {
            return;
        }

        intake.process(message.toOutboundRequest());
    }

    private RequestMessage parse(ConsumerRecord<String, String> record) {
        RequestMessage message;

        try {
            message = jsonMapper.readValue(record.value(), RequestMessage.class);
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("읽을 수 없는 요청 메시지를 건너뜁니다 : {}-{}@{}", record.topic(), record.partition(), record.offset(), e);
            return null;
        }

        if (message == null || !message.isValid()) {
            log.warn("필수 값이 빠진 요청 메시지를 건너뜁니다 : {}-{}@{}", record.topic(), record.partition(), record.offset());
            return null;
        }

        if (!message.targetSystemId().equals(RequestTopics.targetOf(record.topic()))) {
            log.warn("토픽과 대상이 다른 요청 메시지를 건너뜁니다 : {}-{}@{} target={}",
                    record.topic(), record.partition(), record.offset(), message.targetSystemId());
            return null;
        }

        return message;
    }
}
