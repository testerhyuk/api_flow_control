package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.HealthMode;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.LowTrafficEvent;
import com.hyuk.flow_control.domain.health.OutcomeWindow;
import com.hyuk.flow_control.domain.health.RecordedOutcome;
import com.hyuk.flow_control.domain.health.SuppressedBy;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private BaselineEstimator baselines;
    private MaintenanceMonitor maintenance;
    private FlowControlMetrics metrics;

    @BeforeEach
    void setUp() {
        recorder = mock(TargetSystemOutcomeRecorder.class);
        metrics = mock(FlowControlMetrics.class);
        policyReader = mock(TargetSystemPolicyReader.class);
        baselines = mock(BaselineEstimator.class);
        maintenance = mock(MaintenanceMonitor.class);

        TargetSystemPolicy policyA = new TargetSystemPolicy(100, BASELINE_A, 0.95, Duration.ofMillis(10));
        TargetSystemPolicy policyB = new TargetSystemPolicy(50, BASELINE_B, 0.95, Duration.ofMillis(10));
        when(policyReader.readAll()).thenReturn(Map.of(TARGET_A, policyA, TARGET_B, policyB));
        when(policyReader.read(TARGET_A)).thenReturn(policyA);
        when(policyReader.read(TARGET_B)).thenReturn(policyB);
        when(baselines.effective(TARGET_A)).thenReturn(BASELINE_A);
        when(baselines.effective(TARGET_B)).thenReturn(BASELINE_B);
        when(recorder.window(TARGET_A)).thenReturn(OutcomeWindow.empty());
        when(recorder.window(TARGET_B)).thenReturn(OutcomeWindow.empty());
        when(recorder.drainEvents(any())).thenReturn(Set.of());
    }

    @Test
    @DisplayName("판정 전에는 모든 대상이 시작 스냅샷이다")
    void initialSnapshots() {
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        assertThat(evaluator.snapshotOf(TARGET_A)).isEqualTo(HealthSnapshot.initial());
        assertThat(evaluator.snapshotOf(TARGET_B)).isEqualTo(HealthSnapshot.initial());
    }

    @Test
    @DisplayName("설정에 없는 대상의 스냅샷을 찾으면 예외가 난다")
    void throwsForUnknownTarget() {
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        assertThatThrownBy(() -> evaluator.snapshotOf("target-z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("건수가 최소 건수보다 적으면 모두 나쁘다여도 상태는 그대로이고 스냅샷의 근거는 갱신된다")
    void keepsStateWhenNotEnoughSamples() {
        when(recorder.window(TARGET_A)).thenReturn(window(0, 9));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

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
        when(recorder.window(TARGET_A)).thenReturn(new OutcomeWindow(outcomes, Duration.ofSeconds(1)));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.HEALTHY);
        assertThat(evaluator.snapshotOf(TARGET_A).sampleCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("최소 건수와 같으면 판정한다")
    void evaluatesAtMinimum() {
        when(recorder.window(TARGET_A)).thenReturn(window(0, 10));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.CRITICAL);
    }

    @Test
    @DisplayName("나쁘다 비율이 50% 이면 위험이다")
    void criticalAtFiftyPercent() {
        when(recorder.window(TARGET_A)).thenReturn(window(50, 50));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.CRITICAL);
    }

    @Test
    @DisplayName("나쁘다 비율이 49% 이면 위험이 아니라 악화다")
    void degradedJustBelowFiftyPercent() {
        when(recorder.window(TARGET_A)).thenReturn(window(51, 49));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.DEGRADED);
    }

    @Test
    @DisplayName("나쁘다 비율이 10% 이면 악화다")
    void degradedAtTenPercent() {
        when(recorder.window(TARGET_A)).thenReturn(window(90, 10));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.DEGRADED);
    }

    @Test
    @DisplayName("나쁘다 비율이 9% 이고 p95 가 빠르면 정상이다")
    void healthyJustBelowTenPercent() {
        when(recorder.window(TARGET_A)).thenReturn(window(91, 9));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    @DisplayName("p95 가 baseline-rtt 의 2배이면 나쁘다가 없어도 악화다")
    void degradedWhenP95IsTwiceBaseline() {
        when(recorder.window(TARGET_A)).thenReturn(successes(100, Duration.ofMillis(200)));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.DEGRADED);
    }

    @Test
    @DisplayName("p95 가 baseline-rtt 의 2배보다 조금이라도 짧으면 정상이다")
    void healthyWhenP95IsBelowTwiceBaseline() {
        when(recorder.window(TARGET_A)).thenReturn(successes(100, Duration.ofMillis(199)));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    @DisplayName("같은 p95 라도 대상마다 baseline-rtt 가 달라 판정이 다르다")
    void usesBaselinePerTarget() {
        when(recorder.window(TARGET_A)).thenReturn(successes(100, Duration.ofMillis(250)));
        when(recorder.window(TARGET_B)).thenReturn(successes(100, Duration.ofMillis(250)));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluateAll();

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.DEGRADED);
        assertThat(evaluator.snapshotOf(TARGET_B).state()).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    @DisplayName("판정 기준 latency 는 정책 값이 아니라 지금 쓰는 baseline 으로 계산한다")
    void usesEffectiveBaseline() {
        when(baselines.effective(TARGET_A)).thenReturn(Duration.ofMillis(150));
        when(recorder.window(TARGET_A)).thenReturn(successes(100, Duration.ofMillis(250)));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.HEALTHY);
        assertThat(evaluator.snapshotOf(TARGET_A).baselineRtt()).isEqualTo(Duration.ofMillis(150));
    }

    @Test
    @DisplayName("정상으로 판정하면 스냅샷에 건수, 나쁘다 비율, p95, 판정 시각, 모드, 윈도우 시간 범위가 담긴다")
    void snapshotHoldsEvidence() {
        when(recorder.window(TARGET_A)).thenReturn(new OutcomeWindow(outcomes(95, 5), Duration.ofMillis(800)));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        Instant before = Instant.now();
        evaluator.evaluate(TARGET_A);
        Instant after = Instant.now();

        HealthSnapshot snapshot = evaluator.snapshotOf(TARGET_A);
        assertThat(snapshot.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(snapshot.sampleCount()).isEqualTo(100);
        assertThat(snapshot.failureRate()).isEqualTo(0.05);
        assertThat(snapshot.p95()).isEqualTo(FAST);
        assertThat(snapshot.evaluatedAt()).isBetween(before, after);
        assertThat(snapshot.mode()).isEqualTo(HealthMode.ADAPTIVE);
        assertThat(snapshot.windowSpan()).isEqualTo(Duration.ofMillis(800));
        assertThat(snapshot.baselineRtt()).isEqualTo(BASELINE_A);
    }

    @Test
    @DisplayName("위험 후 좋음이 3회 연속되면 회복 중을 거쳐 정상이 된다")
    void recoversAfterRequiredGoods() {
        when(recorder.window(TARGET_A)).thenReturn(critical(), good(), good(), good());
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        assertThat(statesAfterEvaluations(evaluator, 4)).containsExactly(
                HealthState.CRITICAL, HealthState.RECOVERING, HealthState.RECOVERING, HealthState.HEALTHY);
    }

    @Test
    @DisplayName("회복 중에 악화 등급이 나오면 위험이 아니라 악화로 간다")
    void goesToDegradedWhenDegradedWhileRecovering() {
        when(recorder.window(TARGET_A)).thenReturn(critical(), good(), degraded());
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        assertThat(statesAfterEvaluations(evaluator, 3)).containsExactly(
                HealthState.CRITICAL, HealthState.RECOVERING, HealthState.DEGRADED);
    }

    @Test
    @DisplayName("회복 중에 건수가 모자라면 연속 횟수를 늘리지도 지우지도 않는다")
    void keepsGoodCountWhenNotEnoughSamplesWhileRecovering() {
        when(recorder.window(TARGET_A)).thenReturn(critical(), good(), window(5, 0), good(), good());
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        assertThat(statesAfterEvaluations(evaluator, 5)).containsExactly(
                HealthState.CRITICAL, HealthState.RECOVERING, HealthState.RECOVERING,
                HealthState.RECOVERING, HealthState.HEALTHY);
    }

    @Test
    @DisplayName("위험 중에 건수가 모자라면 위험 그대로다")
    void keepsCriticalWhenNotEnoughSamples() {
        when(recorder.window(TARGET_A)).thenReturn(critical(), window(5, 0));
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        assertThat(statesAfterEvaluations(evaluator, 2)).containsExactly(
                HealthState.CRITICAL, HealthState.CRITICAL);
    }

    @Test
    @DisplayName("회복에 필요한 횟수는 recovery-duration 을 evaluation-interval 로 나눠 올림한 값이다")
    void requiredGoodCountRoundsUp() {
        when(recorder.window(TARGET_A)).thenReturn(critical(), good(), good(), good());
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofMillis(2500), false);

        assertThat(statesAfterEvaluations(evaluator, 4)).containsExactly(
                HealthState.CRITICAL, HealthState.RECOVERING, HealthState.RECOVERING, HealthState.HEALTHY);
    }

    @Test
    @DisplayName("진동 방지가 켜져 있으면 악화 등급이 3번 연속이어야 악화가 되고, 그 전에는 막은 횟수를 연속 관측으로 센다")
    void degradedNeedsStreak() {
        when(recorder.window(TARGET_A)).thenReturn(degraded(), degraded(), degraded());
        TargetSystemHealthEvaluator evaluator = antiOscillationEvaluator();

        List<HealthState> states = new ArrayList<>();
        List<Integer> streaks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            evaluator.evaluate(TARGET_A);
            states.add(evaluator.snapshotOf(TARGET_A).state());
            streaks.add(evaluator.snapshotOf(TARGET_A).degradedStreak());
        }

        assertThat(states).containsExactly(HealthState.HEALTHY, HealthState.HEALTHY, HealthState.DEGRADED);
        assertThat(streaks).containsExactly(1, 2, 0);
        verify(metrics, times(2)).recordSuppressed(TARGET_A, SuppressedBy.CONSECUTIVE);
    }

    @Test
    @DisplayName("진동 방지가 켜져 있어도 위험은 바로 바뀐다")
    void criticalIsImmediate() {
        when(recorder.window(TARGET_A)).thenReturn(critical());
        TargetSystemHealthEvaluator evaluator = antiOscillationEvaluator();

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.CRITICAL);
    }

    @Test
    @DisplayName("악화 중 실패율이 복귀 기준과 진입 기준 사이면 악화를 유지하고 막은 횟수를 히스테리시스로 센다")
    void holdsDegradedBetweenThresholds() {
        when(recorder.window(TARGET_A)).thenReturn(degraded(), degraded(), degraded(), window(93, 7));
        TargetSystemHealthEvaluator evaluator = antiOscillationEvaluator();

        assertThat(statesAfterEvaluations(evaluator, 4)).containsExactly(
                HealthState.HEALTHY, HealthState.HEALTHY, HealthState.DEGRADED, HealthState.DEGRADED);
        verify(metrics).recordSuppressed(TARGET_A, SuppressedBy.HYSTERESIS);
    }

    @Test
    @DisplayName("회복 중 사이값이 나오면 회복 중을 유지하고 연속 좋음 횟수를 처음부터 다시 센다")
    void holdResetsGoodCount() {
        when(recorder.window(TARGET_A)).thenReturn(critical(), good(), good(), window(93, 7), good(), good(), good());
        TargetSystemHealthEvaluator evaluator = antiOscillationEvaluator();

        assertThat(statesAfterEvaluations(evaluator, 7)).containsExactly(
                HealthState.CRITICAL, HealthState.RECOVERING, HealthState.RECOVERING, HealthState.RECOVERING,
                HealthState.RECOVERING, HealthState.RECOVERING, HealthState.HEALTHY);
    }

    @Test
    @DisplayName("건수가 모자라고 한도에 막힌 시도도 없는 판정이 3번 연속이면 저트래픽 모드로 들어간다")
    void entersLowTraffic() {
        when(recorder.window(TARGET_A)).thenReturn(window(5, 0));
        TargetSystemHealthEvaluator evaluator = antiOscillationEvaluator();

        List<HealthMode> modes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            evaluator.evaluate(TARGET_A);
            modes.add(evaluator.snapshotOf(TARGET_A).mode());
        }

        assertThat(modes).containsExactly(HealthMode.ADAPTIVE, HealthMode.ADAPTIVE, HealthMode.LOW_TRAFFIC);
        verify(metrics).recordModeTransition(TARGET_A, HealthMode.LOW_TRAFFIC);
    }

    @Test
    @DisplayName("한도에 막힌 시도가 있으면 건수가 모자라도 저트래픽 모드로 들어가지 않는다")
    void doesNotEnterLowTrafficWhenBlocked() {
        when(recorder.window(TARGET_A)).thenReturn(window(5, 0));
        when(recorder.blockedWithinWindow(TARGET_A)).thenReturn(true);
        TargetSystemHealthEvaluator evaluator = antiOscillationEvaluator();

        for (int i = 0; i < 5; i++) {
            evaluator.evaluate(TARGET_A);
        }

        assertThat(evaluator.snapshotOf(TARGET_A).mode()).isEqualTo(HealthMode.ADAPTIVE);
        verify(metrics, never()).recordModeTransition(any(), any());
    }

    @Test
    @DisplayName("저트래픽 모드에서 건수가 모이면 바로 적응 모드로 돌아와 판정한다")
    void leavesLowTrafficWhenEnoughSamples() {
        when(recorder.window(TARGET_A)).thenReturn(window(5, 0), window(5, 0), window(5, 0), critical());
        TargetSystemHealthEvaluator evaluator = antiOscillationEvaluator();

        for (int i = 0; i < 4; i++) {
            evaluator.evaluate(TARGET_A);
        }

        assertThat(evaluator.snapshotOf(TARGET_A).mode()).isEqualTo(HealthMode.ADAPTIVE);
        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.CRITICAL);
        verify(metrics).recordModeTransition(TARGET_A, HealthMode.ADAPTIVE);
    }

    @Test
    @DisplayName("저트래픽 모드에서 사건이 나면 위험으로 내리고 사건을 지표로 센다")
    void lowTrafficEventMakesCritical() {
        when(recorder.window(TARGET_A)).thenReturn(window(5, 0));
        when(recorder.drainEvents(TARGET_A)).thenReturn(
                Set.of(), Set.of(), Set.of(), Set.of(LowTrafficEvent.TOO_MANY_REQUESTS));
        TargetSystemHealthEvaluator evaluator = antiOscillationEvaluator();

        assertThat(statesAfterEvaluations(evaluator, 4)).containsExactly(
                HealthState.HEALTHY, HealthState.HEALTHY, HealthState.HEALTHY, HealthState.CRITICAL);
        verify(metrics).recordLowTrafficEvent(TARGET_A, LowTrafficEvent.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("적응 모드에서 난 저트래픽 사건은 상태를 바꾸지 않는다")
    void ignoresEventsInAdaptiveMode() {
        when(recorder.window(TARGET_A)).thenReturn(good());
        when(recorder.drainEvents(TARGET_A)).thenReturn(Set.of(LowTrafficEvent.CONSECUTIVE_FAILURES));
        TargetSystemHealthEvaluator evaluator = antiOscillationEvaluator();

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.HEALTHY);
        verify(metrics, never()).recordLowTrafficEvent(any(), any());
    }

    @Test
    @DisplayName("저트래픽 모드에서 위험이 된 뒤 recovery-duration 동안 사건이 없으면 정상으로 되돌린다")
    void lowTrafficQuietPeriodRestoresHealthy() throws InterruptedException {
        when(recorder.window(TARGET_A)).thenReturn(window(5, 0));
        when(recorder.drainEvents(TARGET_A)).thenReturn(
                Set.of(), Set.of(), Set.of(LowTrafficEvent.CONSECUTIVE_TIMEOUTS), Set.of());
        TargetSystemHealthEvaluator evaluator = evaluator(Duration.ofMillis(10), Duration.ofMillis(100), true);

        for (int i = 0; i < 4; i++) {
            evaluator.evaluate(TARGET_A);
        }
        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.CRITICAL);

        Thread.sleep(150);
        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.HEALTHY);
        assertThat(evaluator.snapshotOf(TARGET_A).mode()).isEqualTo(HealthMode.LOW_TRAFFIC);
    }

    @Test
    @DisplayName("점검 중에는 판정하지 않고 상태를 그대로 두며 쌓인 사건도 버린다")
    void skipsDuringMaintenance() {
        when(maintenance.isActive(TARGET_A)).thenReturn(true);
        when(recorder.window(TARGET_A)).thenReturn(critical());
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluate(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A)).isEqualTo(HealthSnapshot.initial());
        verify(recorder).drainEvents(TARGET_A);
        verify(recorder, never()).window(TARGET_A);
    }

    @Test
    @DisplayName("점검이 끝나면 회복 중으로 두고 적응 모드에서 다시 판정한다")
    void recoversAfterMaintenance() {
        when(recorder.window(TARGET_A)).thenReturn(window(5, 0));
        TargetSystemHealthEvaluator evaluator = antiOscillationEvaluator();
        for (int i = 0; i < 3; i++) {
            evaluator.evaluate(TARGET_A);
        }

        evaluator.onMaintenanceEnded(TARGET_A);

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.RECOVERING);
        assertThat(evaluator.snapshotOf(TARGET_A).mode()).isEqualTo(HealthMode.ADAPTIVE);
        assertThat(evaluator.statusOf(TARGET_A).consecutiveGoodCount()).isZero();
        verify(metrics).recordStateTransition(TARGET_A, HealthState.HEALTHY, HealthState.RECOVERING);
    }

    @Test
    @DisplayName("한 대상의 판정에서 예외가 나도 나머지 대상은 판정한다")
    void continuesWhenOneTargetFails() {
        when(recorder.window(TARGET_A)).thenThrow(new IllegalStateException("예상하지 못한 예외"));
        when(recorder.window(TARGET_B)).thenReturn(critical());
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluateAll();

        assertThat(evaluator.snapshotOf(TARGET_A)).isEqualTo(HealthSnapshot.initial());
        assertThat(evaluator.snapshotOf(TARGET_B).state()).isEqualTo(HealthState.CRITICAL);
    }

    @Test
    @DisplayName("모든 대상을 판정한다")
    void evaluatesAllTargets() {
        when(recorder.window(TARGET_A)).thenReturn(critical());
        when(recorder.window(TARGET_B)).thenReturn(degraded());
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluateAll();

        assertThat(evaluator.snapshotOf(TARGET_A).state()).isEqualTo(HealthState.CRITICAL);
        assertThat(evaluator.snapshotOf(TARGET_B).state()).isEqualTo(HealthState.DEGRADED);
    }

    @Test
    @DisplayName("상태가 바뀌면 바뀐 상태를 지표로 기록한다")
    void recordsStateTransition() {
        when(recorder.window(TARGET_A)).thenReturn(critical());
        TargetSystemHealthEvaluator evaluator = plainEvaluator();

        evaluator.evaluate(TARGET_A);

        verify(metrics).recordStateTransition(TARGET_A, HealthState.HEALTHY, HealthState.CRITICAL);
    }

    @Test
    @DisplayName("상태가 그대로면 지표를 기록하지 않는다")
    void doesNotRecordWhenStateUnchanged() {
        when(recorder.window(TARGET_A)).thenReturn(good());
        TargetSystemHealthEvaluator evaluator = antiOscillationEvaluator();

        evaluator.evaluate(TARGET_A);

        verifyNoInteractions(metrics);
        verify(recorder, never()).blockedWithinWindow(eq(TARGET_A));
    }

    private TargetSystemHealthEvaluator plainEvaluator() {
        return evaluator(Duration.ofSeconds(3), false);
    }

    private TargetSystemHealthEvaluator antiOscillationEvaluator() {
        return evaluator(Duration.ofSeconds(3), true);
    }

    private TargetSystemHealthEvaluator evaluator(Duration recoveryDuration, boolean antiOscillation) {
        return evaluator(Duration.ofSeconds(1), recoveryDuration, antiOscillation);
    }

    private TargetSystemHealthEvaluator evaluator(Duration interval, Duration recoveryDuration, boolean antiOscillation) {
        HealthProperties properties = new HealthProperties(
                Duration.ofSeconds(10), interval, 10, 0.10, 0.50, 2.0, recoveryDuration,
                antiOscillation, null, 3, null, true, null);
        return new TargetSystemHealthEvaluator(recorder, policyReader, baselines, maintenance, properties, metrics);
    }

    private static List<HealthState> statesAfterEvaluations(TargetSystemHealthEvaluator evaluator, int times) {
        List<HealthState> states = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            evaluator.evaluate(TARGET_A);
            states.add(evaluator.snapshotOf(TARGET_A).state());
        }
        return states;
    }

    private static OutcomeWindow critical() {
        return window(10, 10);
    }

    private static OutcomeWindow degraded() {
        return window(16, 4);
    }

    private static OutcomeWindow good() {
        return window(20, 0);
    }

    private static OutcomeWindow window(int goodCount, int badCount) {
        return new OutcomeWindow(outcomes(goodCount, badCount), Duration.ofSeconds(1));
    }

    private static List<RecordedOutcome> outcomes(int goodCount, int badCount) {
        List<RecordedOutcome> outcomes = new ArrayList<>();
        addMany(outcomes, OutcomeKind.SUCCESS, FAST, goodCount);
        addMany(outcomes, OutcomeKind.TIMEOUT, Duration.ofSeconds(3), badCount);
        return outcomes;
    }

    private static OutcomeWindow successes(int count, Duration latency) {
        List<RecordedOutcome> outcomes = new ArrayList<>();
        addMany(outcomes, OutcomeKind.SUCCESS, latency, count);
        return new OutcomeWindow(outcomes, Duration.ofSeconds(1));
    }

    private static void addMany(List<RecordedOutcome> outcomes, OutcomeKind kind, Duration latency, int count) {
        for (int i = 0; i < count; i++) {
            outcomes.add(new RecordedOutcome(kind, latency, Instant.now()));
        }
    }
}
