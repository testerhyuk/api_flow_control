package com.hyuk.flow_control.adapter.in.kafka;

import com.hyuk.flow_control.adapter.kafka.FailureHeaders;
import com.hyuk.flow_control.adapter.kafka.RequestMessage;
import com.hyuk.flow_control.application.port.in.RequestHandlingUseCase;
import com.hyuk.flow_control.application.service.IntakeInterruptedException;
import com.hyuk.flow_control.domain.retry.DeliveryContext;
import com.hyuk.flow_control.domain.retry.RetryStage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.MessageListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

public class KafkaRequestListener implements MessageListener<String, String> {
    private final String targetSystemId;
    private final RetryStage stage;
    private final RequestHandlingUseCase handling;
    private final JsonMapper jsonMapper;

    public KafkaRequestListener(String targetSystemId, RetryStage stage,
                                RequestHandlingUseCase handling, JsonMapper jsonMapper) {
        this.targetSystemId = targetSystemId;
        this.stage = stage;
        this.handling = handling;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void onMessage(ConsumerRecord<String, String> record) {
        String origin = record.topic() + "-" + record.partition() + "@" + record.offset();
        DeliveryContext context = stage == null
                ? DeliveryContext.first(origin)
                : FailureHeaders.readContext(record.headers(), stage, origin);

        if (stage != null) {
            waitUntil(Instant.ofEpochMilli(record.timestamp()).plus(stage.delay()));
        }

        RequestMessage message;
        try {
            message = jsonMapper.readValue(record.value(), RequestMessage.class);
        } catch (JacksonException | IllegalArgumentException e) {
            handling.rejectInvalid(targetSystemId, record.value(), context, "JSON 이 아닙니다");
            return;
        }

        if (message == null || !message.isValid()) {
            handling.rejectInvalid(targetSystemId, record.value(), context, "필수 값이 빠졌습니다");
            return;
        }

        if (!targetSystemId.equals(message.targetSystemId())) {
            handling.rejectInvalid(targetSystemId, record.value(), context,
                    "토픽의 대상과 메시지의 대상이 다릅니다 : " + message.targetSystemId());
            return;
        }

        handling.handle(message.toOutboundRequest(), context);
    }

    private static void waitUntil(Instant due) {
        Duration remaining = Duration.between(Instant.now(), due);

        if (remaining.isNegative() || remaining.isZero()) {
            return;
        }

        try {
            Thread.sleep(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntakeInterruptedException(e);
        }
    }
}
