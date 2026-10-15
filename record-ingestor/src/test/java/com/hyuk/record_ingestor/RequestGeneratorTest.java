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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestGeneratorTest {
    private static final String TOPIC_A = "flow-control.requests.target-a";
    private static final String TOPIC_C = "flow-control.requests.target-c";

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
    @DisplayName("대상별 토픽에 요청 ID 를 키로 JSON 메시지를 보낸다")
    void sendsJsonToTargetTopic() throws InterruptedException {
        generator = generator(Map.of("target-a", new IngestorProperties.Target(50)));

        generator.start();
        Thread.sleep(300);
        generator.stop();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, atLeast(1)).send(eq(TOPIC_A), key.capture(), value.capture());

        JsonNode message = jsonMapper.readTree(value.getValue());
        assertThat(message.get("requestId").asString()).isEqualTo(key.getValue());
        assertThat(message.get("targetSystemId").asString()).isEqualTo("target-a");
        assertThat(message.get("payload").asString()).startsWith("{\"accountNo\":");
        assertThat(message.get("createdAt").asString()).endsWith("Z");
    }

    @Test
    @DisplayName("간격은 들쭉날쭉해도 평균은 설정한 초당 건수에 맞춰 대상별로 보낸다")
    void sendsAtConfiguredRateOnAverage() throws InterruptedException {
        generator = generator(Map.of(
                "target-a", new IngestorProperties.Target(100),
                "target-c", new IngestorProperties.Target(20)));

        generator.start();
        Thread.sleep(2000);
        generator.stop();

        assertThat(sentTo(TOPIC_A)).isBetween(150L, 250L);
        assertThat(sentTo(TOPIC_C)).isBetween(20L, 60L);
    }

    @Test
    @DisplayName("실행 중에 대상의 요청량을 바꾸면 그때부터 바뀐 양으로 보낸다")
    void changesRate() throws InterruptedException {
        generator = generator(Map.of("target-a", new IngestorProperties.Target(5)));
        generator.start();
        Thread.sleep(500);
        long before = sentTo(TOPIC_A);

        generator.changeRate("target-a", 200);
        Thread.sleep(1000);
        generator.stop();

        assertThat(sentTo(TOPIC_A) - before).isBetween(140L, 260L);
        assertThat(generator.status().targets().get("target-a").sent()).isEqualTo(sentTo(TOPIC_A));
    }

    @Test
    @DisplayName("요청량을 0 으로 바꾸면 그 대상만 멈추고, 처음 보는 대상은 새로 보내기 시작한다")
    void stopsAndAddsTargets() throws InterruptedException {
        generator = generator(Map.of("target-a", new IngestorProperties.Target(100)));
        generator.start();

        generator.changeRate("target-a", 0);
        generator.changeRate("target-c", 100);
        long stoppedAt = sentTo(TOPIC_A);
        Thread.sleep(500);
        generator.stop();

        assertThat(sentTo(TOPIC_A)).isEqualTo(stoppedAt);
        assertThat(sentTo(TOPIC_C)).isPositive();
        assertThat(generator.status().targets()).containsKeys("target-a", "target-c");
        assertThat(generator.status().targets().get("target-a").rate()).isZero();
    }

    @Test
    @DisplayName("seed 와 요청량을 주고 다시 시작하면 이전 대상과 보낸 건수는 지우고 새로 시작한다")
    void restartsWithSeed() throws InterruptedException {
        generator = generator(Map.of("target-a", new IngestorProperties.Target(100)));
        generator.start();
        Thread.sleep(200);

        GeneratorStatus status = generator.start(7, Map.of("target-c", 20.0));

        assertThat(status.running()).isTrue();
        assertThat(status.seed()).isEqualTo(7);
        assertThat(status.targets()).containsOnlyKeys("target-c");
        assertThat(status.targets().get("target-c").sent()).isLessThan(5);
    }

    @Test
    @DisplayName("시작하지 않았으면 요청량을 바꿀 수 없고, 잘못된 요청량은 받지 않는다")
    void rejectsInvalidChanges() {
        generator = generator(Map.of("target-a", new IngestorProperties.Target(100)));

        assertThatThrownBy(() -> generator.changeRate("target-a", 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> generator.start(1, Map.of("target-a", 0.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.start(1, Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("멈추면 더 이상 보내지 않는다")
    void stopsSending() throws InterruptedException {
        generator = generator(Map.of("target-a", new IngestorProperties.Target(100)));

        generator.start();
        Thread.sleep(200);
        generator.stop();
        long sentAtStop = sentTo(TOPIC_A);
        Thread.sleep(200);

        assertThat(generator.isRunning()).isFalse();
        assertThat(sentTo(TOPIC_A)).isEqualTo(sentAtStop);
        assertThat(generator.status().running()).isFalse();
    }

    @Test
    @DisplayName("전송 중 예외가 나도 다음 요청은 계속 보내고, 실패 건수를 센다")
    void keepsSendingAfterFailure() throws InterruptedException {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("전송 실패"))
                .thenReturn(CompletableFuture.completedFuture(null));
        generator = generator(Map.of("target-a", new IngestorProperties.Target(100)));

        generator.start();
        Thread.sleep(300);
        generator.stop();

        assertThat(sentTo(TOPIC_A)).isGreaterThan(1L);
        assertThat(generator.status().targets().get("target-a").failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("자동 시작 설정을 따른다")
    void followsAutoStart() {
        RequestGenerator manual = new RequestGenerator(kafkaTemplate, new RequestMessageFactory(), jsonMapper,
                new IngestorProperties("flow-control.requests.", Map.of("target-a", new IngestorProperties.Target(1)),
                        false, 0));

        assertThat(manual.isAutoStartup()).isFalse();
        assertThat(generator(Map.of("target-a", new IngestorProperties.Target(1))).isAutoStartup()).isTrue();
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
