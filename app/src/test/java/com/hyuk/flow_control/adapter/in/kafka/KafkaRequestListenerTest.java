package com.hyuk.flow_control.adapter.in.kafka;

import com.hyuk.flow_control.adapter.kafka.FailureHeaders;
import com.hyuk.flow_control.application.port.in.RequestHandlingUseCase;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.retry.DeliveryContext;
import com.hyuk.flow_control.domain.retry.RetryStage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class KafkaRequestListenerTest {
    private static final String TOPIC = "flow-control.requests.target-a";
    private static final String VALID = """
            {"requestId":"req-1","targetSystemId":"target-a","payload":"{\\"amount\\":1000}","createdAt":"2026-10-13T09:00:00.123Z"}
            """;

    private RequestHandlingUseCase handling;
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        handling = mock(RequestHandlingUseCase.class);
        jsonMapper = JsonMapper.builder().build();
    }

    @Test
    @DisplayName("원래 토픽의 메시지는 처음 들어온 요청으로 넘긴다")
    void passesFirstDelivery() {
        listener(null).onMessage(record(TOPIC, VALID, System.currentTimeMillis(), new RecordHeaders()));

        verify(handling).handle(
                new OutboundRequest("req-1", "target-a", "{\"amount\":1000}", Instant.parse("2026-10-13T09:00:00.123Z")),
                DeliveryContext.first("flow-control.requests.target-a-3@42"));
    }

    @Test
    @DisplayName("JSON 이 아니면 원래 값 그대로 잘못된 메시지로 넘긴다")
    void rejectsInvalidJson() {
        listener(null).onMessage(record(TOPIC, "not-json", System.currentTimeMillis(), new RecordHeaders()));

        verify(handling).rejectInvalid(eq("target-a"), eq("not-json"), any(), eq("JSON 이 아닙니다"));
        verify(handling, never()).handle(any(), any());
    }

    @Test
    @DisplayName("값이 비어 있거나 필수 값이 빠지면 잘못된 메시지로 넘긴다")
    void rejectsMissingFields() {
        listener(null).onMessage(record(TOPIC, null, System.currentTimeMillis(), new RecordHeaders()));
        listener(null).onMessage(record(TOPIC, """
                {"requestId":"req-1","targetSystemId":"target-a","payload":"{}"}
                """, System.currentTimeMillis(), new RecordHeaders()));

        verify(handling, never()).handle(any(), any());
        verify(handling, times(2)).rejectInvalid(eq("target-a"), any(), any(), any());
    }

    @Test
    @DisplayName("본문이 비어 있으면 잘못된 메시지로 넘긴다")
    void rejectsBlankPayload() {
        listener(null).onMessage(record(TOPIC, """
                {"requestId":"req-1","targetSystemId":"target-a","payload":" ","createdAt":"2026-10-13T09:00:00Z"}
                """, System.currentTimeMillis(), new RecordHeaders()));

        verify(handling).rejectInvalid(eq("target-a"), any(), any(), eq("필수 값이 빠졌습니다"));
        verify(handling, never()).handle(any(), any());
    }

    @Test
    @DisplayName("메시지의 대상이 토픽의 대상과 다르면 잘못된 메시지로 넘긴다")
    void rejectsTargetMismatch() {
        listener(null).onMessage(record(TOPIC, """
                {"requestId":"req-1","targetSystemId":"target-b","payload":"{}","createdAt":"2026-10-13T09:00:00Z"}
                """, System.currentTimeMillis(), new RecordHeaders()));

        verify(handling).rejectInvalid(eq("target-a"), any(), any(), eq("토픽의 대상과 메시지의 대상이 다릅니다 : target-b"));
    }

    @Test
    @DisplayName("재시도 토픽의 메시지는 헤더의 실패 기록을 이어받아 넘긴다")
    void readsContextFromHeaders() {
        Instant firstFailedAt = Instant.parse("2026-10-13T09:00:00Z");
        RecordHeaders headers = new RecordHeaders();
        FailureHeaders.writeContext(headers,
                new DeliveryContext(RetryStage.SECOND, 2, firstFailedAt, firstFailedAt, "flow-control.requests.target-a-1@5"),
                OutcomeKind.TIMEOUT);

        listener(RetryStage.SECOND).onMessage(record("flow-control.retry-10s.target-a", VALID,
                System.currentTimeMillis() - 10_000, headers));

        ArgumentCaptor<DeliveryContext> context = ArgumentCaptor.forClass(DeliveryContext.class);
        verify(handling).handle(any(), context.capture());
        assertThat(context.getValue()).isEqualTo(new DeliveryContext(RetryStage.SECOND, 2, firstFailedAt, firstFailedAt,
                "flow-control.requests.target-a-1@5", OutcomeKind.TIMEOUT));
    }

    @Test
    @DisplayName("재시도 토픽의 메시지는 들어온 시각부터 단계 대기 시간이 지나야 넘긴다")
    void waitsUntilDue() {
        long started = System.nanoTime();

        listener(RetryStage.FIRST).onMessage(record("flow-control.retry-1s.target-a", VALID,
                System.currentTimeMillis(), new RecordHeaders()));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(900));
        verify(handling).handle(any(), any());
    }

    @Test
    @DisplayName("대기 시간이 이미 지난 재시도 메시지는 바로 넘긴다")
    void doesNotWaitWhenOverdue() {
        long started = System.nanoTime();

        listener(RetryStage.FOURTH).onMessage(record("flow-control.retry-300s.target-a", VALID,
                System.currentTimeMillis() - 301_000, new RecordHeaders()));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
        verify(handling).handle(any(), any());
    }

    private KafkaRequestListener listener(RetryStage stage) {
        return new KafkaRequestListener("target-a", stage, handling, jsonMapper);
    }

    private static ConsumerRecord<String, String> record(String topic, String value, long timestamp, RecordHeaders headers) {
        return new ConsumerRecord<>(topic, 3, 42L, timestamp, TimestampType.CREATE_TIME, 0, 0,
                "key", value, headers, Optional.empty());
    }
}
