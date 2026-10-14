package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.port.out.TargetSystemRateLimiter;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.config.LimitAdjustmentProperties;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.support.InMemorySharedRateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalMatchers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TargetSystemLimitAdjusterTest {
    private static final String TARGET_A = "target-a";
    private static final String LOW = "target-low";
    private static final String TINY = "target-tiny";
    private static final TargetSystemPolicy POLICY_A =
            new TargetSystemPolicy(100, Duration.ofMillis(100), 0.95, Duration.ofMillis(10));
    private static final TargetSystemPolicy POLICY_LOW =
            new TargetSystemPolicy(10, Duration.ofMillis(100), 0.95, Duration.ofMillis(10));
    private static final TargetSystemPolicy POLICY_TINY =
            new TargetSystemPolicy(4, Duration.ofMillis(100), 1.0, Duration.ofMillis(10));

    private TargetSystemHealthEvaluator evaluator;
    private TargetSystemPolicyReader policyReader;
    private TargetSystemRateLimiter rateLimiter;
    private TargetSystemSemaphore semaphore;
    private InMemorySharedRateStore sharedRates;
    private FlowControlMetrics metrics;

    @BeforeEach
    void setUp() {
        evaluator = mock(TargetSystemHealthEvaluator.class);
        policyReader = mock(TargetSystemPolicyReader.class);
        rateLimiter = mock(TargetSystemRateLimiter.class);
        semaphore = mock(TargetSystemSemaphore.class);
        sharedRates = new InMemorySharedRateStore();
        metrics = mock(FlowControlMetrics.class);

        when(policyReader.readAll()).thenReturn(Map.of(TARGET_A, POLICY_A, LOW, POLICY_LOW, TINY, POLICY_TINY));
        when(policyReader.read(TARGET_A)).thenReturn(POLICY_A);
        when(policyReader.read(LOW)).thenReturn(POLICY_LOW);
        when(policyReader.read(TINY)).thenReturn(POLICY_TINY);
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(HealthSnapshot.initial());
        when(evaluator.snapshotOf(LOW)).thenReturn(HealthSnapshot.initial());
        when(evaluator.snapshotOf(TINY)).thenReturn(HealthSnapshot.initial());
    }

    @Test
    @DisplayName("시작 rate 는 대상별 실제 Rate 다")
    void startsAtEffectiveRate() {
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        assertThat(adjuster.rateOf(TARGET_A)).isEqualTo(95.0);
        assertThat(adjuster.appliedRateOf(TARGET_A)).isEqualTo(95.0);
        assertThat(adjuster.rateOf(LOW)).isEqualTo(9.5);
        assertThat(adjuster.serversOf(TARGET_A)).isEqualTo(1);
    }

    @Test
    @DisplayName("설정에 없는 대상의 rate 를 찾으면 예외가 난다")
    void throwsForUnknownTarget() {
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        assertThatThrownBy(() -> adjuster.rateOf("target-z")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adjuster.appliedRateOf("target-z")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("첫 조절에서는 rate 가 그대로여도 한도와 concurrency 를 한 번 맞춘다")
    void appliesOnFirstAdjustment() {
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.HEALTHY, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjust(TARGET_A);

        verify(rateLimiter).changeRate(TARGET_A, 95.0);
        verify(semaphore).changeLimit(TARGET_A, 10);
    }

    @Test
    @DisplayName("rate 가 그대로면 concurrency 는 다시 바꾸지 않는다")
    void keepsConcurrencyWhenRateUnchanged() {
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.HEALTHY, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(50);
        adjuster.adjust(TARGET_A);
        clearInvocations(semaphore);

        adjuster.adjust(TARGET_A);

        assertThat(adjuster.appliedRateOf(TARGET_A)).isEqualTo(95.0);
        verify(semaphore, never()).changeLimit(eq(TARGET_A), anyInt());
    }

    @Test
    @DisplayName("CRITICAL 이면 초당 minimum-calls ÷ window 건으로 바로 줄이고 concurrency 도 같이 줄인다")
    void dropsToCriticalRate() {
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.CRITICAL, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjust(TARGET_A);

        assertThat(adjuster.rateOf(TARGET_A)).isEqualTo(5.0);
        assertThat(adjuster.appliedRateOf(TARGET_A)).isEqualTo(5.0);
        verify(rateLimiter).changeRate(TARGET_A, 5.0);
        verify(semaphore).changeLimit(TARGET_A, 1);
    }

    @Test
    @DisplayName("CRITICAL rate 는 minimum-calls 를 바꾸면 따라 바뀐다")
    void criticalRateFollowsMinimumCalls() {
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.CRITICAL, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(100);

        adjuster.adjust(TARGET_A);

        assertThat(adjuster.rateOf(TARGET_A)).isEqualTo(10.0);
    }

    @Test
    @DisplayName("CRITICAL rate 가 처음 rate 보다 크면 처음 rate 를 그대로 둔다")
    void neverRaisesAboveEffectiveRateOnCritical() {
        when(evaluator.snapshotOf(TINY)).thenReturn(snapshot(HealthState.CRITICAL, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjust(TINY);

        assertThat(adjuster.rateOf(TINY)).isEqualTo(4.0);
    }

    @Test
    @DisplayName("DEGRADED 이면 처음 rate 의 degraded-ratio 로 바로 줄인다")
    void dropsToDegradedRatio() {
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.DEGRADED, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjust(TARGET_A);

        assertThat(adjuster.rateOf(TARGET_A)).isEqualTo(47.5);
        verify(semaphore).changeLimit(TARGET_A, 5);
    }

    @Test
    @DisplayName("DEGRADED 로 줄인 값이 CRITICAL rate 보다 작으면 CRITICAL rate 에서 멈춘다")
    void degradedNeverGoesBelowCriticalRate() {
        when(evaluator.snapshotOf(LOW)).thenReturn(snapshot(HealthState.DEGRADED, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjust(LOW);

        assertThat(adjuster.rateOf(LOW)).isEqualTo(5.0);
    }

    @Test
    @DisplayName("CRITICAL 에서 DEGRADED 가 되어도 rate 를 올리지 않는다")
    void doesNotRaiseOnDegradedAfterCritical() {
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(
                snapshot(HealthState.CRITICAL, 100),
                snapshot(HealthState.DEGRADED, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjust(TARGET_A);
        adjuster.adjust(TARGET_A);

        assertThat(adjuster.rateOf(TARGET_A)).isEqualTo(5.0);
    }

    @Test
    @DisplayName("RECOVERING 이고 건수가 충분하면 판정마다 처음 rate 의 recovery-step 만큼 늘린다")
    void raisesByStepWhileRecovering() {
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(
                snapshot(HealthState.CRITICAL, 100),
                snapshot(HealthState.RECOVERING, 100),
                snapshot(HealthState.RECOVERING, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjust(TARGET_A);
        adjuster.adjust(TARGET_A);
        assertThat(adjuster.rateOf(TARGET_A)).isCloseTo(9.75, within(1e-9));

        adjuster.adjust(TARGET_A);
        assertThat(adjuster.rateOf(TARGET_A)).isCloseTo(14.5, within(1e-9));

        verify(rateLimiter).changeRate(eq(TARGET_A), AdditionalMatchers.eq(9.75, 1e-9));
        verify(rateLimiter).changeRate(eq(TARGET_A), AdditionalMatchers.eq(14.5, 1e-9));
        verify(semaphore).changeLimit(TARGET_A, 2);
    }

    @Test
    @DisplayName("RECOVERING 이어도 건수가 모자라면 rate 를 늘리지 않는다")
    void doesNotRaiseWithoutEnoughSamples() {
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(
                snapshot(HealthState.CRITICAL, 100),
                snapshot(HealthState.RECOVERING, 49));
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjust(TARGET_A);
        adjuster.adjust(TARGET_A);

        assertThat(adjuster.rateOf(TARGET_A)).isEqualTo(5.0);
    }

    @Test
    @DisplayName("HEALTHY 에서도 100% 가 될 때까지 늘리고 처음 rate 를 넘지 않는다")
    void raisesUpToEffectiveRate() {
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.DEGRADED, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(50);
        adjuster.adjust(TARGET_A);

        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.HEALTHY, 100));
        for (int i = 0; i < 20; i++) {
            adjuster.adjust(TARGET_A);
        }

        assertThat(adjuster.rateOf(TARGET_A)).isEqualTo(95.0);
        verify(rateLimiter, never()).changeRate(eq(TARGET_A), AdditionalMatchers.gt(95.0));
    }

    @Test
    @DisplayName("다른 서버가 더 낮은 rate 를 원하면 그 값을 적용하고, 이 서버가 원하는 rate 는 따로 기억한다")
    void appliesLowestProposal() {
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.HEALTHY, 100));
        sharedRates.proposeFromOtherServer(TARGET_A, "other", 47.5);
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjust(TARGET_A);

        assertThat(adjuster.rateOf(TARGET_A)).isEqualTo(95.0);
        assertThat(adjuster.appliedRateOf(TARGET_A)).isEqualTo(47.5);
        assertThat(adjuster.serversOf(TARGET_A)).isEqualTo(2);
        assertThat(sharedRates.storedAppliedRate(TARGET_A)).contains(47.5);
        verify(rateLimiter).changeRate(TARGET_A, 47.5);
        verify(semaphore).changeLimit(TARGET_A, 5);
    }

    @Test
    @DisplayName("낮은 값을 원하던 서버가 사라지면 이 서버가 원하는 rate 로 돌아간다")
    void returnsToOwnRateWhenOtherServerExpires() {
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.HEALTHY, 100));
        sharedRates.proposeFromOtherServer(TARGET_A, "other", 47.5);
        TargetSystemLimitAdjuster adjuster = adjuster(50);
        adjuster.adjust(TARGET_A);

        sharedRates.expireOtherServer(TARGET_A, "other");
        adjuster.adjust(TARGET_A);

        assertThat(adjuster.appliedRateOf(TARGET_A)).isEqualTo(95.0);
        assertThat(adjuster.serversOf(TARGET_A)).isEqualTo(1);
    }

    @Test
    @DisplayName("처음 공용 저장소에 닿으면 적용돼 있던 rate 를 이어받고, 판정이 좋으면 조금씩 올린다")
    void inheritsAppliedRate() {
        sharedRates.storeAppliedRate(TARGET_A, 47.5);
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.HEALTHY, 10));
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjust(TARGET_A);
        assertThat(adjuster.rateOf(TARGET_A)).isEqualTo(47.5);
        assertThat(adjuster.appliedRateOf(TARGET_A)).isEqualTo(47.5);

        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.HEALTHY, 100));
        adjuster.adjust(TARGET_A);
        assertThat(adjuster.rateOf(TARGET_A)).isCloseTo(52.25, within(1e-9));
    }

    @Test
    @DisplayName("이어받을 rate 는 한 번만 읽는다")
    void inheritsOnlyOnce() {
        sharedRates.storeAppliedRate(TARGET_A, 47.5);
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.HEALTHY, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(50);
        adjuster.adjust(TARGET_A);

        sharedRates.storeAppliedRate(TARGET_A, 5.0);
        adjuster.adjust(TARGET_A);

        assertThat(adjuster.rateOf(TARGET_A)).isGreaterThan(47.5);
    }

    @Test
    @DisplayName("공용 저장소를 쓸 수 없으면 이 서버가 원하는 rate 를 그대로 적용하고 오류를 센다")
    void usesOwnRateWhenStoreUnavailable() {
        sharedRates.becomeUnavailable(true);
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.DEGRADED, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjust(TARGET_A);

        assertThat(adjuster.appliedRateOf(TARGET_A)).isEqualTo(47.5);
        assertThat(adjuster.serversOf(TARGET_A)).isEqualTo(1);
        verify(rateLimiter).changeRate(TARGET_A, 47.5);
        verify(metrics, atLeastOnce()).recordRedisError("proposal", true);
    }

    @Test
    @DisplayName("Redis 를 쓸 수 없는 상태라 아예 시도하지 않았으면 오류로 세지 않는다")
    void doesNotCountSkippedCalls() {
        sharedRates.becomeUnavailable(false);
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjust(TARGET_A);

        verify(metrics, never()).recordRedisError(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("한도 변경에 실패하면 rate 를 바뀐 것으로 기록하지 않는다")
    void keepsRateWhenChangeFails() {
        when(evaluator.snapshotOf(TARGET_A)).thenReturn(snapshot(HealthState.CRITICAL, 100));
        doThrow(new IllegalStateException("예상하지 못한 예외")).when(rateLimiter).changeRate(eq(TARGET_A), anyDouble());
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        assertThatThrownBy(() -> adjuster.adjust(TARGET_A)).isInstanceOf(IllegalStateException.class);

        assertThat(adjuster.rateOf(TARGET_A)).isEqualTo(95.0);
        assertThat(adjuster.appliedRateOf(TARGET_A)).isEqualTo(95.0);
        verify(semaphore, never()).changeLimit(eq(TARGET_A), anyInt());
    }

    @Test
    @DisplayName("한 대상의 조절에서 예외가 나도 나머지 대상은 조절한다")
    void continuesWhenOneTargetFails() {
        when(evaluator.snapshotOf(TARGET_A)).thenThrow(new IllegalStateException("예상하지 못한 예외"));
        when(evaluator.snapshotOf(LOW)).thenReturn(snapshot(HealthState.CRITICAL, 100));
        TargetSystemLimitAdjuster adjuster = adjuster(50);

        adjuster.adjustAll();

        assertThat(adjuster.rateOf(TARGET_A)).isEqualTo(95.0);
        assertThat(adjuster.rateOf(LOW)).isEqualTo(5.0);
        verify(rateLimiter).changeRate(LOW, 5.0);
    }

    private TargetSystemLimitAdjuster adjuster(int minimumCalls) {
        HealthProperties healthProperties = new HealthProperties(
                Duration.ofSeconds(10), Duration.ofSeconds(1), minimumCalls, 0.10, 0.50, 2.0, Duration.ofSeconds(30));
        LimitAdjustmentProperties adjustmentProperties = new LimitAdjustmentProperties(0.5, 0.05);

        return new TargetSystemLimitAdjuster(evaluator, policyReader, rateLimiter, semaphore, sharedRates, metrics,
                adjustmentProperties, healthProperties);
    }

    private static HealthSnapshot snapshot(HealthState state, int sampleCount) {
        return new HealthSnapshot(state, sampleCount, 0.0, null, Instant.now());
    }
}
