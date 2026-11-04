package com.hyuk.record_ingestor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.Invocation;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestGeneratorTest {
    private static final String TOPIC_A = "flow-control.requests.target-a.record-ingestor";
    private static final String TOPIC_C = "flow-control.requests.target-c.record-ingestor";
    private static final long LONG_DURATION = 600;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private KafkaTemplate<String, String> kafkaTemplate;
    private TopicChecker topicChecker;
    private RequestGenerator generator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        topicChecker = mock(TopicChecker.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        generator = new RequestGenerator(kafkaTemplate, new RequestMessageFactory(), jsonMapper,
                new IngestorProperties("flow-control.requests.", "record-ingestor"), topicChecker);
    }

    @AfterEach
    void tearDown() {
        generator.stopGenerating();
    }

    private static ExperimentPlan plan(Map<String, Double> rates) {
        return new ExperimentPlan(1, rates, List.of(), LONG_DURATION);
    }

    private static ExperimentPlan.RateChange change(long atSeconds, String target, double rate) {
        return new ExperimentPlan.RateChange(atSeconds, target, rate);
    }

    @Test
    @DisplayName("켜도 시작하기 전에는 아무것도 보내지 않고 STOPPED 다")
    void doesNotSendBeforeStart() throws InterruptedException {
        Thread.sleep(100);

        assertThat(generator.status().state()).isEqualTo(GeneratorStatus.State.STOPPED);
        assertThat(generator.status().running()).isFalse();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("대상별 토픽에 요청 ID 를 키로 JSON 메시지를 보낸다")
    void sendsJsonToTargetTopic() throws InterruptedException {
        generator.start(plan(Map.of("target-a", 50.0)));
        Thread.sleep(300);
        generator.stopGenerating();

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
        generator.start(plan(Map.of("target-a", 100.0, "target-c", 20.0)));
        Thread.sleep(2000);
        generator.stopGenerating();

        assertThat(sentTo(TOPIC_A)).isBetween(150L, 250L);
        assertThat(sentTo(TOPIC_C)).isBetween(20L, 60L);
    }

    @Test
    @DisplayName("실행 중에 대상의 요청량을 바꾸면 그때부터 바뀐 양으로 보낸다")
    void changesRate() throws InterruptedException {
        generator.start(plan(Map.of("target-a", 5.0)));
        Thread.sleep(500);
        long before = sentTo(TOPIC_A);

        generator.changeRate("target-a", 200);
        Thread.sleep(1000);
        generator.stopGenerating();

        assertThat(sentTo(TOPIC_A) - before).isBetween(140L, 260L);
        assertThat(generator.status().targets().get("target-a").sent()).isEqualTo(sentTo(TOPIC_A));
    }

    @Test
    @DisplayName("요청량을 0 으로 바꾸면 그 대상만 쉬고, 처음 보는 대상은 토픽을 확인한 뒤 보내기 시작한다")
    void pausesAndAddsTargets() throws InterruptedException {
        generator.start(plan(Map.of("target-a", 100.0)));

        generator.changeRate("target-a", 0);
        Thread.sleep(50);
        long pausedAt = sentTo(TOPIC_A);
        generator.changeRate("target-c", 100);
        Thread.sleep(500);
        generator.stopGenerating();

        verify(topicChecker).requireExisting(List.of(TOPIC_C));
        assertThat(sentTo(TOPIC_A)).isEqualTo(pausedAt);
        assertThat(sentTo(TOPIC_C)).isPositive();
        assertThat(generator.status().targets()).containsKeys("target-a", "target-c");
        assertThat(generator.status().targets().get("target-a").rate()).isZero();
    }

    @Test
    @DisplayName("실행 중에 넣는 새 대상의 토픽이 없으면 거부하고 돌던 대상은 계속 보낸다")
    void rejectsNewTargetWithoutTopic() {
        generator.start(plan(Map.of("target-a", 100.0)));
        doThrow(new IllegalStateException("토픽이 없습니다")).when(topicChecker).requireExisting(List.of(TOPIC_C));

        assertThatThrownBy(() -> generator.changeRate("target-c", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("토픽이 없습니다");

        assertThat(generator.status().targets()).containsOnlyKeys("target-a");
        assertThat(generator.status().running()).isTrue();
    }

    @Test
    @DisplayName("이미 도는 대상의 요청량을 바꾸거나, 없는 대상을 0 으로 바꿀 때는 토픽을 확인하지 않는다")
    void doesNotCheckTopicWhenNotNeeded() {
        generator.start(plan(Map.of("target-a", 100.0)));

        generator.changeRate("target-a", 10);
        generator.changeRate("target-c", 0);

        verify(topicChecker, never()).requireExisting(List.of(TOPIC_C));
        assertThat(generator.status().targets()).containsOnlyKeys("target-a");
    }

    @Test
    @DisplayName("시작할 때 요청량과 시간표에 있는 모든 대상의 토픽을 확인한다")
    void checksAllTopicsOnStart() {
        generator.start(new ExperimentPlan(1, Map.of("target-a", 10.0),
                List.of(change(100, "target-c", 5)), LONG_DURATION));

        ArgumentCaptor<List<String>> topics = ArgumentCaptor.captor();
        verify(topicChecker).requireExisting(topics.capture());
        assertThat(topics.getValue()).containsExactlyInAnyOrder(TOPIC_A, TOPIC_C);
    }

    @Test
    @DisplayName("토픽이 없어 시작을 거부하면 돌던 생성은 끊기지 않는다")
    void keepsRunningWhenStartRejected() throws InterruptedException {
        generator.start(new ExperimentPlan(7, Map.of("target-a", 100.0), List.of(), LONG_DURATION));
        doThrow(new IllegalStateException("토픽이 없습니다")).when(topicChecker).requireExisting(any());

        assertThatThrownBy(() -> generator.start(plan(Map.of("target-c", 10.0))))
                .isInstanceOf(IllegalStateException.class);

        long before = sentTo(TOPIC_A);
        Thread.sleep(200);
        assertThat(generator.status().seed()).isEqualTo(7);
        assertThat(generator.status().running()).isTrue();
        assertThat(sentTo(TOPIC_A)).isGreaterThan(before);
        assertThat(sentTo(TOPIC_C)).isZero();
    }

    @Test
    @DisplayName("seed 와 요청량을 주고 다시 시작하면 이전 대상과 보낸 건수는 지우고 새로 시작한다")
    void restartsWithSeed() throws InterruptedException {
        generator.start(plan(Map.of("target-a", 100.0)));
        Thread.sleep(200);

        GeneratorStatus status = generator.start(new ExperimentPlan(7, Map.of("target-c", 20.0), List.of(), LONG_DURATION));

        assertThat(status.running()).isTrue();
        assertThat(status.seed()).isEqualTo(7);
        assertThat(status.targets()).containsOnlyKeys("target-c");
        assertThat(status.targets().get("target-c").sent()).isLessThan(5);
    }

    @Test
    @DisplayName("시작하지 않았으면 요청량을 바꿀 수 없고, 잘못된 요청량은 받지 않는다")
    void rejectsInvalidChanges() {
        assertThatThrownBy(() -> generator.changeRate("target-a", 10)).isInstanceOf(IllegalStateException.class);

        generator.start(plan(Map.of("target-a", 1.0)));
        assertThatThrownBy(() -> generator.changeRate("target-a", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("시간표의 시각이 되면 스스로 요청량을 바꾼다")
    void followsTimeline() throws InterruptedException {
        generator.start(new ExperimentPlan(1, Map.of("target-a", 5.0),
                List.of(change(1, "target-a", 300)), LONG_DURATION));

        Thread.sleep(900);
        long beforeChange = sentTo(TOPIC_A);
        assertThat(generator.status().targets().get("target-a").rate()).isEqualTo(5.0);

        Thread.sleep(1100);
        assertThat(generator.status().targets().get("target-a").rate()).isEqualTo(300.0);
        generator.stopGenerating();

        assertThat(beforeChange).isLessThan(20);
        assertThat(sentTo(TOPIC_A) - beforeChange).isGreaterThan(150);
    }

    @Test
    @DisplayName("시간표에만 있는 대상은 그 시각부터 보내고, rate 0 이면 다음 변경까지 쉰다")
    void startsAndPausesByTimeline() throws InterruptedException {
        generator.start(new ExperimentPlan(1, Map.of("target-a", 100.0),
                List.of(change(1, "target-c", 200), change(1, "target-a", 0)), LONG_DURATION));

        Thread.sleep(800);
        assertThat(sentTo(TOPIC_C)).isZero();
        assertThat(generator.status().targets().get("target-c").rate()).isZero();

        Thread.sleep(500);
        long pausedA = sentTo(TOPIC_A);
        Thread.sleep(200);
        generator.stopGenerating();

        assertThat(sentTo(TOPIC_C)).isPositive();
        assertThat(sentTo(TOPIC_A)).isEqualTo(pausedA);
    }

    @Test
    @DisplayName("손으로 바꾼 요청량은 다음 시간표 시각에 덮어써진다")
    void timelineOverridesManualChange() throws InterruptedException {
        generator.start(new ExperimentPlan(1, Map.of("target-a", 5.0),
                List.of(change(1, "target-a", 7)), LONG_DURATION));

        generator.changeRate("target-a", 50);
        assertThat(generator.status().targets().get("target-a").rate()).isEqualTo(50.0);

        Thread.sleep(1200);
        assertThat(generator.status().targets().get("target-a").rate()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("같은 seed, 시간표, 실험 길이면 보내는 건수가 정확히 같다")
    void sameInputsSendSameCount() throws InterruptedException {
        ExperimentPlan plan = new ExperimentPlan(3, Map.of("target-a", 200.0),
                List.of(change(1, "target-a", 400)), 2);

        generator.start(plan);
        awaitStopped();
        long first = generator.status().targets().get("target-a").sent();

        generator.start(plan);
        awaitStopped();
        long second = generator.status().targets().get("target-a").sent();

        assertThat(first).isPositive().isEqualTo(second);
    }

    @Test
    @DisplayName("스레드가 늦어져도 밀린 만큼 보내 건수가 같고, 늦어진 시간과 연달아 보낸 건수를 보여준다")
    @SuppressWarnings("unchecked")
    void catchesUpAndReportsLag() throws InterruptedException {
        ExperimentPlan plan = new ExperimentPlan(3, Map.of("target-a", 200.0), List.of(), 1);
        generator.start(plan);
        awaitStopped();
        long expected = generator.status().targets().get("target-a").sent();
        assertThat(generator.status().targets().get("target-a").catchUpSent()).isLessThan(expected);

        AtomicBoolean blocked = new AtomicBoolean();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            if (blocked.compareAndSet(false, true)) {
                Thread.sleep(300);
            }
            return CompletableFuture.completedFuture((SendResult<String, String>) null);
        });

        generator.start(plan);
        awaitStopped();

        GeneratorStatus.Target target = generator.status().targets().get("target-a");
        assertThat(target.sent()).isEqualTo(expected);
        assertThat(target.maxLagMillis()).isGreaterThanOrEqualTo(200);
        assertThat(target.catchUpSent()).isGreaterThan(10);
    }

    @Test
    @DisplayName("실험 길이가 지나면 스스로 멈춘다")
    void stopsAtDuration() throws InterruptedException {
        GeneratorStatus started = generator.start(new ExperimentPlan(1, Map.of("target-a", 100.0), List.of(), 1));
        assertThat(started.state()).isEqualTo(GeneratorStatus.State.RUNNING);

        awaitStopped();

        long sentAtStop = sentTo(TOPIC_A);
        Thread.sleep(200);
        assertThat(generator.status().running()).isFalse();
        assertThat(generator.status().targets().get("target-a").rate()).isZero();
        assertThat(sentTo(TOPIC_A)).isEqualTo(sentAtStop).isBetween(60L, 140L);
    }

    @Test
    @DisplayName("멈추기는 넘긴 요청의 결과가 다 나올 때까지 기다렸다가 최종 건수를 돌려준다")
    @SuppressWarnings("unchecked")
    void stopWaitsForPendingResults() throws InterruptedException {
        List<CompletableFuture<SendResult<String, String>>> results = new ArrayList<>();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            CompletableFuture<SendResult<String, String>> result = new CompletableFuture<>();
            synchronized (results) {
                results.add(result);
            }
            return result;
        });
        generator.start(plan(Map.of("target-a", 200.0)));
        Thread.sleep(100);

        AtomicReference<GeneratorStatus> stopped = new AtomicReference<>();
        Thread stopper = new Thread(() -> stopped.set(generator.stopGenerating()));
        stopper.start();
        Thread.sleep(200);

        assertThat(stopper.isAlive()).isTrue();
        assertThat(generator.status().state()).isEqualTo(GeneratorStatus.State.STOPPING);
        assertThat(generator.status().running()).isFalse();

        int total;
        synchronized (results) {
            total = results.size();
            results.get(0).completeExceptionally(new IllegalStateException("브로커 고장"));
            results.stream().skip(1).forEach(result -> result.complete(null));
        }
        stopper.join(2000);

        GeneratorStatus.Target target = stopped.get().targets().get("target-a");
        assertThat(stopped.get().state()).isEqualTo(GeneratorStatus.State.STOPPED);
        assertThat(target.failed()).isEqualTo(1);
        assertThat(target.sent()).isEqualTo(total - 1);
    }

    @Test
    @DisplayName("전송 중 예외가 나도 다음 요청은 계속 보내고, 실패 건수를 세고, 멈출 수 있다")
    void keepsSendingAfterFailure() throws InterruptedException {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("전송 실패"))
                .thenReturn(CompletableFuture.completedFuture(null));

        generator.start(plan(Map.of("target-a", 100.0)));
        Thread.sleep(300);
        GeneratorStatus stopped = generator.stopGenerating();

        assertThat(stopped.state()).isEqualTo(GeneratorStatus.State.STOPPED);
        assertThat(sentTo(TOPIC_A)).isGreaterThan(1L);
        assertThat(stopped.targets().get("target-a").failed()).isEqualTo(1);
    }

    private void awaitStopped() throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (generator.status().state() != GeneratorStatus.State.STOPPED) {
            assertThat(System.nanoTime()).as("STOPPED 가 되지 않았습니다").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private long sentTo(String topic) {
        List<Invocation> invocations = List.copyOf(mockingDetails(kafkaTemplate).getInvocations());
        return invocations.stream()
                .filter(invocation -> invocation.getMethod().getName().equals("send"))
                .filter(invocation -> topic.equals(invocation.getArgument(0)))
                .count();
    }
}
