package com.hyuk.record_ingestor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestGeneratorTest {
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private KafkaTemplate<String, String> kafkaTemplate;
    private RequestGenerator generator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
    }

    @AfterEach
    void tearDown() {
        if (generator != null) {
            generator.stop();
        }
    }

    @Test
    @DisplayName("초당 건수로 보내는 간격을 계산한다")
    void periodOfRate() {
        assertThat(RequestGenerator.periodNanosOf(100)).isEqualTo(10_000_000L);
        assertThat(RequestGenerator.periodNanosOf(20)).isEqualTo(50_000_000L);
        assertThat(RequestGenerator.periodNanosOf(0.5)).isEqualTo(2_000_000_000L);
    }

    @Test
    @DisplayName("대상별 토픽에 요청 ID 를 키로 JSON 메시지를 보낸다")
    void sendsJsonToTargetTopic() throws InterruptedException {
        generator = generator(Map.of("target-a", new IngestorProperties.Target(10)));

        generator.start();
        Thread.sleep(150);
        generator.stop();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, atLeast(1)).send(eq("flow-control.requests.target-a"), key.capture(), value.capture());

        JsonNode message = jsonMapper.readTree(value.getValue());
        assertThat(message.get("requestId").asString()).isEqualTo(key.getValue());
        assertThat(message.get("targetSystemId").asString()).isEqualTo("target-a");
        assertThat(message.get("payload").asString()).startsWith("{\"accountNo\":");
        assertThat(message.get("createdAt").asString()).endsWith("Z");
    }

    @Test
    @DisplayName("설정한 초당 건수만큼 대상별로 고르게 보낸다")
    void sendsAtConfiguredRate() throws InterruptedException {
        generator = generator(Map.of(
                "target-a", new IngestorProperties.Target(100),
                "target-c", new IngestorProperties.Target(20)));

        generator.start();
        Thread.sleep(1000);
        generator.stop();

        assertThat(sentTo("flow-control.requests.target-a")).isBetween(80L, 120L);
        assertThat(sentTo("flow-control.requests.target-c")).isBetween(15L, 25L);
    }

    @Test
    @DisplayName("멈추면 더 이상 보내지 않는다")
    void stopsSending() throws InterruptedException {
        generator = generator(Map.of("target-a", new IngestorProperties.Target(100)));

        generator.start();
        Thread.sleep(200);
        generator.stop();
        long sentAtStop = sentTo("flow-control.requests.target-a");
        Thread.sleep(200);

        assertThat(generator.isRunning()).isFalse();
        assertThat(sentTo("flow-control.requests.target-a")).isEqualTo(sentAtStop);
    }

    @Test
    @DisplayName("전송 중 예외가 나도 다음 요청은 계속 보낸다")
    void keepsSendingAfterFailure() throws InterruptedException {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("전송 실패"))
                .thenReturn(CompletableFuture.completedFuture(null));
        generator = generator(Map.of("target-a", new IngestorProperties.Target(100)));

        generator.start();
        Thread.sleep(200);
        generator.stop();

        assertThat(sentTo("flow-control.requests.target-a")).isGreaterThan(1L);
    }

    private RequestGenerator generator(Map<String, IngestorProperties.Target> targets) {
        return new RequestGenerator(kafkaTemplate, new RequestMessageFactory(), jsonMapper,
                new IngestorProperties("flow-control.requests.", targets));
    }

    private long sentTo(String topic) {
        return mockingDetails(kafkaTemplate).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("send"))
                .filter(invocation -> topic.equals(invocation.getArgument(0)))
                .count();
    }
}
