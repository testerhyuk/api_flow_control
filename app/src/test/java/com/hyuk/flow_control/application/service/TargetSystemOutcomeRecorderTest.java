package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.LowTrafficEvent;
import com.hyuk.flow_control.domain.health.OutcomeWindow;
import com.hyuk.flow_control.domain.health.RecordedOutcome;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TargetSystemOutcomeRecorderTest {
    private static final String TARGET_A = "target-a";
    private static final String TARGET_B = "target-b";

    @Test
    @DisplayName("처음에는 기록된 결과가 없다")
    void emptyAtFirst() {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        assertThat(recorder.recentOutcomes(TARGET_A)).isEmpty();
        assertThat(recorder.recentOutcomes(TARGET_B)).isEmpty();
    }

    @Test
    @DisplayName("기록한 순서대로 kind 와 latency 를 돌려준다")
    void returnsInRecordedOrder() {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        recorder.record(TARGET_A, OutcomeKind.SUCCESS, Duration.ofMillis(120));
        recorder.record(TARGET_A, OutcomeKind.TIMEOUT, Duration.ofMillis(3000));
        recorder.record(TARGET_A, OutcomeKind.BUSINESS_REJECTED, Duration.ofMillis(80));

        List<RecordedOutcome> outcomes = recorder.recentOutcomes(TARGET_A);

        assertThat(outcomes).extracting(RecordedOutcome::kind)
                .containsExactly(OutcomeKind.SUCCESS, OutcomeKind.TIMEOUT, OutcomeKind.BUSINESS_REJECTED);
        assertThat(outcomes).extracting(RecordedOutcome::latency)
                .containsExactly(Duration.ofMillis(120), Duration.ofMillis(3000), Duration.ofMillis(80));
    }

    @Test
    @DisplayName("기록 시점은 record 를 호출한 시각이다")
    void stampsRecordedAt() {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        Instant before = Instant.now();
        recorder.record(TARGET_A, OutcomeKind.SUCCESS, Duration.ofMillis(120));
        Instant after = Instant.now();

        assertThat(recorder.recentOutcomes(TARGET_A).getFirst().recordedAt()).isBetween(before, after);
    }

    @Test
    @DisplayName("대상 시스템마다 따로 기록한다")
    void separatesTargets() {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        recorder.record(TARGET_A, OutcomeKind.SUCCESS, Duration.ofMillis(120));
        recorder.record(TARGET_B, OutcomeKind.SERVER_ERROR, Duration.ofMillis(50));
        recorder.record(TARGET_B, OutcomeKind.TIMEOUT, Duration.ofMillis(3000));

        assertThat(recorder.recentOutcomes(TARGET_A)).extracting(RecordedOutcome::kind)
                .containsExactly(OutcomeKind.SUCCESS);
        assertThat(recorder.recentOutcomes(TARGET_B)).extracting(RecordedOutcome::kind)
                .containsExactly(OutcomeKind.SERVER_ERROR, OutcomeKind.TIMEOUT);
    }

    @Test
    @DisplayName("window 보다 오래된 결과는 돌려주지 않는다")
    void dropsOutcomesOlderThanWindow() throws InterruptedException {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofMillis(200));

        recorder.record(TARGET_A, OutcomeKind.TIMEOUT, Duration.ofMillis(3000));
        Thread.sleep(400);
        recorder.record(TARGET_A, OutcomeKind.SUCCESS, Duration.ofMillis(120));

        assertThat(recorder.recentOutcomes(TARGET_A)).extracting(RecordedOutcome::kind)
                .containsExactly(OutcomeKind.SUCCESS);
    }

    @Test
    @DisplayName("window 안에서도 최신 minimum-calls 건까지만 담는다")
    void keepsLatestSamplesWithinWindow() {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        for (int i = 0; i < 100; i++) {
            recorder.record(TARGET_A, OutcomeKind.TIMEOUT, Duration.ofMillis(3000));
        }
        for (int i = 0; i < 50; i++) {
            recorder.record(TARGET_A, OutcomeKind.SUCCESS, Duration.ofMillis(120));
        }

        assertThat(recorder.recentOutcomes(TARGET_A)).hasSize(50)
                .extracting(RecordedOutcome::kind)
                .containsOnly(OutcomeKind.SUCCESS);
    }

    @Test
    @DisplayName("판정에서 빼는 결과는 건수에 넣지 않고 건너뛴다")
    void skipsExcludedKinds() {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        recorder.record(TARGET_A, OutcomeKind.SUCCESS, Duration.ofMillis(120));
        recorder.record(TARGET_A, OutcomeKind.UNEXPECTED_RESPONSE, Duration.ofMillis(80));

        assertThat(recorder.recentOutcomes(TARGET_A)).extracting(RecordedOutcome::kind)
                .containsExactly(OutcomeKind.SUCCESS);
    }

    @Test
    @DisplayName("윈도우의 시간 범위는 담긴 결과 중 가장 오래된 것부터 최신까지다")
    void reportsSpan() throws InterruptedException {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        assertThat(recorder.window(TARGET_A).span()).isNull();

        recorder.record(TARGET_A, OutcomeKind.SUCCESS, Duration.ofMillis(120));
        Thread.sleep(100);
        recorder.record(TARGET_A, OutcomeKind.SUCCESS, Duration.ofMillis(120));

        OutcomeWindow window = recorder.window(TARGET_A);
        assertThat(window.span()).isBetween(Duration.ofMillis(90), Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("한도에 막힌 시도는 window 동안만 남는다")
    void remembersBlockedWithinWindow() throws InterruptedException {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofMillis(200));

        assertThat(recorder.blockedWithinWindow(TARGET_A)).isFalse();

        recorder.recordBlocked(TARGET_A);
        assertThat(recorder.blockedWithinWindow(TARGET_A)).isTrue();
        assertThat(recorder.blockedWithinWindow(TARGET_B)).isFalse();

        Thread.sleep(300);
        assertThat(recorder.blockedWithinWindow(TARGET_A)).isFalse();
    }

    @Test
    @DisplayName("429 는 실패로 세서, 3번 연속이면 연속 실패 사건이고, 꺼내면 비워진다")
    void tooManyRequestsCountsAsFailure() {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        recorder.record(TARGET_A, OutcomeKind.TOO_MANY_REQUESTS, Duration.ofMillis(10));
        assertThat(recorder.drainEvents(TARGET_A)).isEmpty();

        recorder.record(TARGET_A, OutcomeKind.TOO_MANY_REQUESTS, Duration.ofMillis(10));
        recorder.record(TARGET_A, OutcomeKind.TOO_MANY_REQUESTS, Duration.ofMillis(10));

        assertThat(recorder.drainEvents(TARGET_A)).containsExactly(LowTrafficEvent.CONSECUTIVE_FAILURES);
        assertThat(recorder.drainEvents(TARGET_A)).isEmpty();
    }

    @Test
    @DisplayName("실패가 3번 연속이면 연속 실패 사건이고, 중간에 성공이 오면 처음부터 다시 센다")
    void consecutiveFailuresEvent() {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        recorder.record(TARGET_A, OutcomeKind.SERVER_ERROR, Duration.ofMillis(10));
        recorder.record(TARGET_A, OutcomeKind.SERVER_ERROR, Duration.ofMillis(10));
        recorder.record(TARGET_A, OutcomeKind.SUCCESS, Duration.ofMillis(10));
        recorder.record(TARGET_A, OutcomeKind.SERVER_ERROR, Duration.ofMillis(10));
        recorder.record(TARGET_A, OutcomeKind.CONNECTION_FAILED, Duration.ofMillis(10));
        assertThat(recorder.drainEvents(TARGET_A)).isEmpty();

        recorder.record(TARGET_A, OutcomeKind.SERVER_ERROR, Duration.ofMillis(10));
        assertThat(recorder.drainEvents(TARGET_A)).containsExactly(LowTrafficEvent.CONSECUTIVE_FAILURES);
    }

    @Test
    @DisplayName("timeout 이 3번 연속이면 연속 실패와 연속 timeout 사건이 함께 남는다")
    void consecutiveTimeoutsEvent() {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        recorder.record(TARGET_A, OutcomeKind.TIMEOUT, Duration.ofSeconds(3));
        recorder.record(TARGET_A, OutcomeKind.TIMEOUT, Duration.ofSeconds(3));
        recorder.record(TARGET_A, OutcomeKind.TIMEOUT, Duration.ofSeconds(3));

        assertThat(recorder.drainEvents(TARGET_A)).containsExactlyInAnyOrder(
                LowTrafficEvent.CONSECUTIVE_FAILURES, LowTrafficEvent.CONSECUTIVE_TIMEOUTS);
    }

    @Test
    @DisplayName("timeout 사이에 다른 실패가 끼면 연속 timeout 은 처음부터 다시 센다")
    void otherFailureBreaksTimeoutStreak() {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        recorder.record(TARGET_A, OutcomeKind.TIMEOUT, Duration.ofSeconds(3));
        recorder.record(TARGET_A, OutcomeKind.TIMEOUT, Duration.ofSeconds(3));
        recorder.record(TARGET_A, OutcomeKind.SERVER_ERROR, Duration.ofMillis(10));
        recorder.drainEvents(TARGET_A);
        recorder.record(TARGET_A, OutcomeKind.TIMEOUT, Duration.ofSeconds(3));

        assertThat(recorder.drainEvents(TARGET_A)).doesNotContain(LowTrafficEvent.CONSECUTIVE_TIMEOUTS);
    }

    @Test
    @DisplayName("설정에 없는 대상이면 기록할 때 예외가 난다")
    void throwsWhenRecordingUnknownTarget() {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        assertThatThrownBy(() -> recorder.record("target-z", OutcomeKind.SUCCESS, Duration.ofMillis(120)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("설정에 없는 대상이면 조회할 때 예외가 난다")
    void throwsWhenReadingUnknownTarget() {
        TargetSystemOutcomeRecorder recorder = recorderWithWindow(Duration.ofSeconds(10));

        assertThatThrownBy(() -> recorder.recentOutcomes("target-z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TargetSystemOutcomeRecorder recorderWithWindow(Duration window) {
        TargetSystemPolicyReader policyReader = mock(TargetSystemPolicyReader.class);
        TargetSystemPolicy policy = new TargetSystemPolicy(100, Duration.ofMillis(100), 0.95, Duration.ofMillis(10));
        when(policyReader.readAll()).thenReturn(Map.of(TARGET_A, policy, TARGET_B, policy));

        HealthProperties properties = new HealthProperties(
                window, Duration.ofSeconds(1), 50, 0.10, 0.50, 2.0, Duration.ofSeconds(30));

        return new TargetSystemOutcomeRecorder(policyReader, properties);
    }
}
