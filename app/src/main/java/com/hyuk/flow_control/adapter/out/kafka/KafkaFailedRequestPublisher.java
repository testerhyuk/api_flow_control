package com.hyuk.flow_control.adapter.out.kafka;

import com.hyuk.flow_control.adapter.kafka.FailureHeaders;
import com.hyuk.flow_control.adapter.kafka.RequestMessage;
import com.hyuk.flow_control.adapter.kafka.RequestTopics;
import com.hyuk.flow_control.application.port.out.FailedRequestPublisher;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.retry.DeadLetterReason;
import com.hyuk.flow_control.domain.retry.DeliveryContext;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaFailedRequestPublisher implements FailedRequestPublisher {
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    public KafkaFailedRequestPublisher(KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void scheduleRetry(OutboundRequest request, DeliveryContext context, OutcomeKind lastKind) {
        if (context.stage() == null) {
            throw new IllegalArgumentException("재시도 단계가 정해지지 않았습니다");
        }

        ProducerRecord<String, String> record = new ProducerRecord<>(
                RequestTopics.retry(context.stage(), request.targetSystemId()),
                request.requestId(),
                toJson(request));
        FailureHeaders.writeContext(record.headers(), context, lastKind);

        send(record);
    }

    @Override
    public void deadLetter(OutboundRequest request, DeliveryContext context, DeadLetterReason reason,
                           OutboundResult lastResult, String error) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                RequestTopics.deadLetter(request.targetSystemId()),
                request.requestId(),
                toJson(request));
        FailureHeaders.writeContext(record.headers(), context, lastResult == null ? null : lastResult.kind());
        FailureHeaders.writeDeadLetter(record.headers(), reason, error);
        if (lastResult != null) {
            FailureHeaders.writeResponseCodes(record.headers(), lastResult.statusCode(), lastResult.resultCode());
        }

        send(record);
    }

    @Override
    public void deadLetterRaw(String targetSystemId, String rawValue, DeliveryContext context, String error) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                RequestTopics.deadLetter(targetSystemId),
                null,
                rawValue);
        FailureHeaders.writeContext(record.headers(), context, null);
        FailureHeaders.writeDeadLetter(record.headers(), DeadLetterReason.INVALID_MESSAGE, error);

        send(record);
    }

    private String toJson(OutboundRequest request) {
        return jsonMapper.writeValueAsString(RequestMessage.from(request));
    }

    private void send(ProducerRecord<String, String> record) {
        try {
            kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("실패 요청 전송 중 중단되었습니다 : " + record.topic(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("실패 요청 전송에 실패했습니다 : " + record.topic(), e);
        }
    }
}
