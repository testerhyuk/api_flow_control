package com.hyuk.flow_control.adapter.out.kafka;

import com.hyuk.flow_control.domain.expiry.ExpiryPoint;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.result.FinalOutcome;
import com.hyuk.flow_control.domain.result.RequestResult;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaResultPublisherTest {
    private static final Instant FINISHED = Instant.parse("2026-11-01T09:00:00Z");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private KafkaTemplate<String, String> kafkaTemplate;
    private KafkaResultPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        publisher = new KafkaResultPublisher(kafkaTemplate, jsonMapper);
    }

    @Test
    @DisplayName("완료 결과는 요청자의 결과 토픽에 요청 ID 를 키로, 외부 서버 응답을 담은 JSON 으로 보낸다")
    void publishesCompleted() {
        OutboundResult success = new OutboundResult(
                OutcomeKind.SUCCESS, Duration.ofMillis(100), null, "{\"grade\":1}", 200, "0000");

        publisher.publish("loan", RequestResult.called("req-1", "target-a", FinalOutcome.COMPLETED, success, 1,
                FINISHED));

        ProducerRecord<String, String> sent = captured();
        assertThat(sent.topic()).isEqualTo("flow-control.results.loan");
        assertThat(sent.key()).isEqualTo("req-1");
        JsonNode json = jsonMapper.readTree(sent.value());
        assertThat(json.get("requestId").asString()).isEqualTo("req-1");
        assertThat(json.get("targetSystemId").asString()).isEqualTo("target-a");
        assertThat(json.get("outcome").asString()).isEqualTo("completed");
        assertThat(json.get("kind").asString()).isEqualTo("SUCCESS");
        assertThat(json.get("statusCode").asInt()).isEqualTo(200);
        assertThat(json.get("resultCode").asString()).isEqualTo("0000");
        assertThat(json.get("responseBody").asString()).isEqualTo("{\"grade\":1}");
        assertThat(json.get("attempts").asInt()).isEqualTo(1);
        assertThat(Instant.parse(json.get("finishedAt").asString())).isEqualTo(FINISHED);
        assertThat(json.has("deadLetterReason")).isFalse();
        assertThat(json.has("rawMessage")).isFalse();
    }

    @Test
    @DisplayName("만료 결과는 만료 지점을 담고, 없는 값은 넣지 않는다")
    void publishesExpired() {
        publisher.publish("loan", RequestResult.expired("req-1", "target-a", ExpiryPoint.LIMIT_WAIT, null, 0,
                FINISHED));

        JsonNode json = jsonMapper.readTree(captured().value());
        assertThat(json.get("outcome").asString()).isEqualTo("expired");
        assertThat(json.get("expiredWhere").asString()).isEqualTo("limit_wait");
        assertThat(json.get("attempts").asInt()).isZero();
        assertThat(json.has("kind")).isFalse();
        assertThat(json.has("statusCode")).isFalse();
    }

    @Test
    @DisplayName("잘못된 메시지 결과는 키 없이 원래 값을 담아 보낸다")
    void publishesInvalidMessage() {
        publisher.publish("loan", RequestResult.invalidMessage("target-a", "not-json", FINISHED));

        ProducerRecord<String, String> sent = captured();
        assertThat(sent.key()).isNull();
        JsonNode json = jsonMapper.readTree(sent.value());
        assertThat(json.has("requestId")).isFalse();
        assertThat(json.get("outcome").asString()).isEqualTo("dead_lettered");
        assertThat(json.get("deadLetterReason").asString()).isEqualTo("invalid_message");
        assertThat(json.get("rawMessage").asString()).isEqualTo("not-json");
    }

    @Test
    @DisplayName("Kafka 전송에 실패하면 성공할 때까지 다시 넣는다")
    @SuppressWarnings("unchecked")
    void retriesUntilSendSucceeds() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("브로커 없음")))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        publisher = new KafkaResultPublisher(kafkaTemplate, jsonMapper, Duration.ofMillis(1));

        publisher.publish("loan", RequestResult.invalidMessage("target-a", "not-json", FINISHED));

        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> captured() {
        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(record.capture());
        return record.getValue();
    }
}
