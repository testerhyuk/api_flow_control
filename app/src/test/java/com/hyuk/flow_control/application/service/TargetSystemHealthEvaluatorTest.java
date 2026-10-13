package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.RecordedOutcome;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TargetSystemHealthEvaluatorTest {
    private static final String TARGET_A = "target-a";
    private static final String TARGET_B = "target-b";
    private static final Duration BASELINE_A = Duration.ofMillis(100);
    private static final Duration BASELINE_B = Duration.ofMillis(300);
    private static final Duration FAST = Duration.ofMillis(100);

    private TargetSystemOutcomeRecorder recorder;
    private TargetSystemPolicyReader policyReader;
    private FlowControlMetrics metrics;

    @BeforeEach
    void setUp() {
        recorder = mock(TargetSystemOutcomeRecorder.class);
        metrics = mock(FlowControlMetrics.class);
        policyReader = mock(TargetSystemPolicyReader.class);

        TargetSystemPolicy policyA = new TargetSystemPolicy(100, BASELINE_A, 0.95, Duration.ofMillis(10));
        TargetSystemPolicy policyB = new TargetSystemPolicy(50, BASELINE_B, 0.95, Duration.ofMillis(10));
        when(policyReader.readAll()).thenReturn(Map.of(TARGET_A, policyA, TARGET_B, policyB));
        when(policyReader.read(TARGET_A)).thenReturn(policyA);
        when(policyReader.read(TARGET_B)).thenReturn(policyB);
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(List.of());
        when(recorder.recentOutcomes(TARGET_B)).thenReturn(List.of());
    }

    @Test
    @DisplayName("판정 전에는 모든 대상이 시작 스냅샷이다")
    void initialSnapshots() {
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        assertThat(evaluator.snapshotOf(TARGET_A)).isEqualTo(HealthSnapshot.initial());
        assertThat(evaluator.snapshotOf(TARGET_B)).isEqualTo(HealthSnapshot.initial());
    }

    @Test
    @DisplayName("설정에 없는 대상의 스냅샷을 찾으면 예외가 난다")
    void throwsForUnknownTarget() {
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        assertThatThrownBy(() -> evaluator.snapshotOf("target-z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("건수가 최소 건수보다 적으면 모두 나쁘다여도 상태는 그대로이고 스냅샷의 근거는 갱신된다")
    void keepsStateWhenNotEnoughSamples() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(outcomes(0, 9));
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluate(TARGET_A);

        HealthSnapshot snapshot = evaluator.snapshotOf(TARGET_A);
        assertThat(snapshot.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(snapshot.sampleCount()).isEqualTo(9);
        assertThat(snapshot.failureRate()).isEqualTo(1.0);
        assertThat(snapshot.evaluatedAt()).isNotNull();
    }

    @Test
    @DisplayName("UNEXPECTED_RESPONSE 는 최소 건수에 들어가지 않는다")
    void excludedKindsDoNotCountTowardMinimum() {
        List<RecordedOutcome> outcomes = new ArrayList<>(outcomes(0, 5));
        addMany(outcomes, OutcomeKind.UNEXPECTED_RESPONSE, FAST, 20);
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(outcomes);
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.HEALTHY);
        assertThat(evaluator.snapshotOf(TARGET_A).sampleCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("최소 건수와 같으면 판정한다")
    void evaluatesAtMinimum() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(outcomes(0, 10));
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.CRITICAL);
    }

    @Test
    @DisplayName("나쁘다 비율이 50% 이면 위험이다")
    void criticalAtFiftyPercent() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(outcomes(50, 50));
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.CRITICAL);
    }

    @Test
    @DisplayName("나쁘다 비율이 49% 이면 위험이 아니라 악화다")
    void degradedJustBelowFiftyPercent() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(outcomes(51, 49));
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.DEGRADED);
    }

    @Test
    @DisplayName("나쁘다 비율이 10% 이면 악화다")
    void degradedAtTenPercent() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(outcomes(90, 10));
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.DEGRADED);
    }

    @Test
    @DisplayName("나쁘다 비율이 9% 이고 p95 가 빠르면 정상이다")
    void healthyJustBelowTenPercent() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(outcomes(91, 9));
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    @DisplayName("p95 가 baseline-rtt 의 2배이면 나쁘다가 없어도 악화다")
    void degradedWhenP95IsTwiceBaseline() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(successes(100, Duration.ofMillis(200)));
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.DEGRADED);
    }

    @Test
    @DisplayName("p95 가 baseline-rtt 의 2배보다 조금이라도 짧으면 정상이다")
    void healthyWhenP95IsBelowTwiceBaseline() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(successes(100, Duration.ofMillis(199)));
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    @DisplayName("같은 p95 라도 대상마다 baseline-rtt 가 달라 판정이 다르다")
    void usesBaselinePerTarget() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(successes(100, Duration.ofMillis(250)));
        when(recorder.recentOutcomes(TARGET_B)).thenReturn(successes(100, Duration.ofMillis(250)));
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluateAll();

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.DEGRADED);
        assertThat(evaluator.snapshotOf(TARGET_B).state()).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    @DisplayName("정상으로 판정하면 스냅샷에 건수, 나쁘다 비율, p95, 판정 시각이 담긴다")
    void snapshotHoldsEvidence() {
        List<RecordedOutcome> outcomes = new ArrayList<>(outcomes(95, 5));
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(outcomes);
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        Instant before = Instant.now();
        evaluator.evaluate(TARGET_A);
        Instant after = Instant.now();

        HealthSnapshot snapshot = evaluator.snapshotOf(TARGET_A);
        assertThat(snapshot.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(snapshot.sampleCount()).isEqualTo(100);
        assertThat(snapshot.failureRate()).isEqualTo(0.05);
        assertThat(snapshot.p95()).isEqualTo(FAST);
        assertThat(snapshot.evaluatedAt()).isBetween(before, after);
    }

    @Test
    @DisplayName("위험 후 좋음이 3회 연속되면 회복 중을 거쳐 정상이 된다")
    void recoversAfterRequiredGoods() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(critical(), good(), good(), good());
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        assertThat(statesAfterEvaluations(evaluator, 4)).containsExactly(
                HealthState.CRITICAL, HealthState.RECOVERING, HealthState.RECOVERING, HealthState.HEALTHY);
    }

    @Test
    @DisplayName("회복 중에 악화 등급이 나오면 위험으로 돌아간다")
    void relapsesToCriticalWhenDegradedWhileRecovering() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(critical(), good(), degraded());
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        assertThat(statesAfterEvaluations(evaluator, 3)).containsExactly(
                HealthState.CRITICAL, HealthState.RECOVERING, HealthState.CRITICAL);
    }

    @Test
    @DisplayName("회복 중에 건수가 모자라면 연속 횟수를 늘리지도 지우지도 않는다")
    void keepsGoodCountWhenNotEnoughSamplesWhileRecovering() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(critical(), good(), outcomes(5, 0), good(), good());
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        assertThat(statesAfterEvaluations(evaluator, 5)).containsExactly(
                HealthState.CRITICAL, HealthState.RECOVERING, HealthState.RECOVERING,
                HealthState.RECOVERING, HealthState.HEALTHY);
    }

    @Test
    @DisplayName("위험 중에 건수가 모자라면 위험 그대로다")
    void keepsCriticalWhenNotEnoughSamples() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(critical(), outcomes(5, 0));
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        assertThat(statesAfterEvaluations(evaluator, 2)).containsExactly(
                HealthState.CRITICAL, HealthState.CRITICAL);
    }

    @Test
    @DisplayName("회복에 필요한 횟수는 recovery-duration 을 evaluation-interval 로 나눠 올림한 값이다")
    void requiredGoodCountRoundsUp() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(critical(), good(), good(), good());
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofMillis(2500));

        assertThat(statesAfterEvaluations(evaluator, 4)).containsExactly(
                HealthState.CRITICAL, HealthState.RECOVERING, HealthState.RECOVERING, HealthState.HEALTHY);
    }

    @Test
    @DisplayName("한 대상의 판정에서 예외가 나도 나머지 대상은 판정한다")
    void continuesWhenOneTargetFails() {
        when(recorder.recentOutcomes(TARGET_A)).thenThrow(new IllegalStateException("예상하지 못한 예외"));
        when(recorder.recentOutcomes(TARGET_B)).thenReturn(critical());
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluateAll();

        assertThat(evaluator.snapshotOf(TARGET_A)).isEqualTo(HealthSnapshot.initial());
        assertThat(evaluator.snapshotOf(TARGET_B).state()).isEqualTo(HealthState.CRITICAL);
    }

    @Test
    @DisplayName("모든 대상을 판정한다")
    void evaluatesAllTargets() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(critical());
        when(recorder.recentOutcomes(TARGET_B)).thenReturn(degraded());
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluateAll();

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.CRITICAL);
        assertThat(evaluator.snapshotOf(TARGET_B).state()).isEqualTo(HealthState.DEGRADED);
    }

    @Test
    @DisplayName("상태가 바뀌면 바뀐 상태를 지표로 기록한다")
    void recordsStateTransition() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(critical());
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluate(TARGET_A);

        verify(metrics).recordStateTransition(TARGET_A, HealthState.HEALTHY, HealthState.CRITICAL);
    }

    @Test
    @DisplayName("상태가 그대로면 지표를 기록하지 않는다")
    void doesNotRecordWhenStateUnchanged() {
        when(recorder.recentOutcomes(TARGET_A)).thenReturn(good());
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofSeconds(3));

        evaluator.evaluate(TARGET_A);

        verifyNoInteractions(metrics);
    }

    private TargetSystemHealthEvaluator evaluator(Duration recoveryDuration) {
        HealthProperties properties = new HealthProperties(
                Duration.ofSeconds(10), Duration.ofSeconds(1), 10, 0.10, 0.50, 2.0, recoveryDuration);
        return new TargetSystemHealthEvaluator(recorder, policyReader, properties, metrics);
    }

    private static List<HealthState> statesAfterEvaluations(TargetSystemHealthEvaluator evaluator, int times) {
        List<HealthState> states = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            evaluator.evaluate(TARGET_A);
            states.add(evaluator.snapshotOf(TARGET_A).state());
        }
        return states;
    }

    private static List<RecordedOutcome> critical() {
        return outcomes(10, 10);
    }

    private static List<RecordedOutcome> degraded() {
        return outcomes(16, 4);
    }

    private static List<RecordedOutcome> good() {
        return outcomes(20, 0);
    }

    private static List<RecordedOutcome> outcomes(int goodCount, int badCount) {
        List<RecordedOutcome> outcomes = new ArrayList<>();
        addMany(outcomes, OutcomeKind.SUCCESS, FAST, goodCount);
        addMany(outcomes, OutcomeKind.TIMEOUT, Duration.ofSeconds(3), badCount);
        return outcomes;
    }

    private static List<RecordedOutcome> successes(int count, Duration latency) {
        List<RecordedOutcome> outcomes = new ArrayList<>();
        addMany(outcomes, OutcomeKind.SUCCESS, latency, count);
        return outcomes;
    }

    private static void addMany(List<RecordedOutcome> outcomes, OutcomeKind kind, Duration latency, int count) {
        for (int i = 0; i < count; i++) {
            outcomes.add(new RecordedOutcome(kind, latency, Instant.now()));
        }
    }
}
