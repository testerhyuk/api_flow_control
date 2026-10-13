package com.hyuk.flow_control.adapter.out.kafka;

import com.hyuk.flow_control.adapter.kafka.FailureHeaders;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.retry.DeadLetterReason;
import com.hyuk.flow_control.domain.retry.DeliveryContext;
import com.hyuk.flow_control.domain.retry.RetryStage;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaFailedRequestPublisherTest {
    private static final Instant CREATED = Instant.parse("2026-10-13T09:00:00Z");
    private static final Instant FAILED = Instant.parse("2026-10-13T09:00:03Z");
    private static final OutboundRequest REQUEST = new OutboundRequest("req-1", "target-a", "{\"amount\":1000}", CREATED);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private KafkaTemplate<String, String> kafkaTemplate;
    private KafkaFailedRequestPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        publisher = new KafkaFailedRequestPublisher(kafkaTemplate, jsonMapper);
    }

    @Test
    @DisplayName("재시도는 단계 토픽에 요청 ID 를 키로, 원래 요청 JSON 을 값으로, 실패 기록을 헤더로 보낸다")
    void schedulesRetry() {
        DeliveryContext context = new DeliveryContext(RetryStage.SECOND, 2, FAILED, FAILED, "origin-0@1");

        publisher.scheduleRetry(REQUEST, context, OutcomeKind.TIMEOUT);

        ProducerRecord<String, String> sent = captured();
        assertThat(sent.topic()).isEqualTo("flow-control.retry-10s.target-a");
        assertThat(sent.key()).isEqualTo("req-1");
        assertOriginalJson(sent.value());
        assertThat(FailureHeaders.readContext(sent.headers(), RetryStage.SECOND, "x")).isEqualTo(context);
        assertThat(FailureHeaders.read(sent.headers(), FailureHeaders.LAST_KIND)).isEqualTo("TIMEOUT");
        assertThat(sent.headers().lastHeader(FailureHeaders.DEAD_LETTER_REASON)).isNull();
    }

    @Test
    @DisplayName("단계가 없는 실패 기록으로는 재시도를 보낼 수 없다")
    void rejectsRetryWithoutStage() {
        assertThatThrownBy(() -> publisher.scheduleRetry(REQUEST, DeliveryContext.first("o"), OutcomeKind.TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DLQ 는 대상 DLQ 토픽에 원래 요청 JSON 과 이유, 실패 기록을 보낸다")
    void deadLetters() {
        DeliveryContext context = new DeliveryContext(RetryStage.FOURTH, 5, CREATED, FAILED, "origin-0@1");

        publisher.deadLetter(REQUEST, context, DeadLetterReason.RETRY_EXHAUSTED, OutcomeKind.SERVER_ERROR, null);

        ProducerRecord<String, String> sent = captured();
        assertThat(sent.topic()).isEqualTo("flow-control.dlq.target-a");
        assertThat(sent.key()).isEqualTo("req-1");
        assertOriginalJson(sent.value());
        assertThat(FailureHeaders.read(sent.headers(), FailureHeaders.DEAD_LETTER_REASON)).isEqualTo("retry_exhausted");
        assertThat(FailureHeaders.read(sent.headers(), FailureHeaders.FAILED_ATTEMPTS)).isEqualTo("5");
        assertThat(FailureHeaders.read(sent.headers(), FailureHeaders.LAST_KIND)).isEqualTo("SERVER_ERROR");
        assertThat(FailureHeaders.read(sent.headers(), FailureHeaders.ORIGIN)).isEqualTo("origin-0@1");
    }

    @Test
    @DisplayName("잘못된 메시지는 원래 값을 바꾸지 않고 DLQ 에 보낸다")
    void deadLettersRawValue() {
        publisher.deadLetterRaw("target-a", "not-json", DeliveryContext.first("origin-0@1").afterFailure(FAILED), "JSON 이 아닙니다");

        ProducerRecord<String, String> sent = captured();
        assertThat(sent.topic()).isEqualTo("flow-control.dlq.target-a");
        assertThat(sent.value()).isEqualTo("not-json");
        assertThat(FailureHeaders.read(sent.headers(), FailureHeaders.DEAD_LETTER_REASON)).isEqualTo("invalid_message");
        assertThat(FailureHeaders.read(sent.headers(), FailureHeaders.ERROR)).isEqualTo("JSON 이 아닙니다");
    }

    @Test
    @DisplayName("Kafka 전송에 실패하면 예외를 낸다")
    void throwsWhenSendFails() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("브로커 없음")));

        assertThatThrownBy(() -> publisher.deadLetter(REQUEST, DeliveryContext.first("o"),
                DeadLetterReason.DLQ_KIND, OutcomeKind.INVALID_REQUEST, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> captured() {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private void assertOriginalJson(String value) {
        JsonNode json = jsonMapper.readTree(value);
        assertThat(json.get("requestId").asString()).isEqualTo("req-1");
        assertThat(json.get("targetSystemId").asString()).isEqualTo("target-a");
        assertThat(json.get("payload").asString()).isEqualTo("{\"amount\":1000}");
        assertThat(Instant.parse(json.get("createdAt").asString())).isEqualTo(CREATED);
    }
}
