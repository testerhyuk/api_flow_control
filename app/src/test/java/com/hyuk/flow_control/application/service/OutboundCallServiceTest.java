package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.TargetSystemClient;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.port.out.TargetSystemRateLimiter;
import com.hyuk.flow_control.config.ControlProperties;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.limit.ControlMode;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.CallAttempt;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.token.RateLimitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OutboundCallServiceTest {
    private static final String TARGET = "target-a";
    private static final OutboundRequest REQUEST =
            new OutboundRequest("req-1", TARGET, "{}", Instant.now());
    private static final OutboundResult RESULT =
            new OutboundResult(OutcomeKind.SUCCESS, Duration.ofMillis(120), null, null, null, null);

    private TargetSystemSemaphore semaphore;
    private TargetSystemRateLimiter rateLimiter;
    private TargetSystemClient client;
    private OutboundCallService service;
    private TargetSystemOutcomeRecorder recorder;
    private FlowControlMetrics metrics;
    private DryRunMonitor dryRuns;
    private TargetSystemHealthEvaluator evaluator;
    private BaselineEstimator baselines;
    private MaintenanceMonitor maintenance;

    @BeforeEach
    void setUp() {
        TargetSystemPolicyReader policyReader = mock(TargetSystemPolicyReader.class);
        when(policyReader.readAll()).thenReturn(Map.of(
                TARGET, new TargetSystemPolicy(1, Duration.ofSeconds(1), 1.0, Duration.ofMillis(10))
        ));

        semaphore = new TargetSystemSemaphore(policyReader);
        rateLimiter = mock(TargetSystemRateLimiter.class);
        client = mock(TargetSystemClient.class);
        recorder = mock(TargetSystemOutcomeRecorder.class);
        metrics = mock(FlowControlMetrics.class);
        dryRuns = mock(DryRunMonitor.class);
        when(dryRuns.beforeCall(TARGET)).thenReturn(DryRunMonitor.Observation.NONE);
        evaluator = mock(TargetSystemHealthEvaluator.class);
        when(evaluator.snapshotOf(TARGET)).thenReturn(HealthSnapshot.initial());
        baselines = mock(BaselineEstimator.class);
        maintenance = mock(MaintenanceMonitor.class);
        service = service(ControlMode.ADAPTIVE);
    }

    private OutboundCallService service(ControlMode mode) {
        return new OutboundCallService(client, semaphore, rateLimiter, recorder, evaluator, baselines, maintenance,
                metrics, dryRuns, new ControlProperties(mode));
    }

    @Test
    @DisplayName("세마포어와 토큰을 모두 통과하면 외부를 호출하고 Called 를 돌려준다")
    void returnsCalledWhenBothPass() {
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenReturn(RESULT);

        CallAttempt attempt = service.send(REQUEST);

        assertThat(attempt).isEqualTo(new CallAttempt.Called(RESULT));
        verify(recorder).record(TARGET, OutcomeKind.SUCCESS, Duration.ofMillis(120));
        verify(metrics).recordCall(TARGET, OutcomeKind.SUCCESS, Duration.ofMillis(120));
        verifyNoMoreInteractions(metrics);
    }

    @Test
    @DisplayName("세마포어에서 막히면 BlockedBySemaphore 를 돌려주고 토큰과 외부 호출을 건드리지 않는다")
    void returnsBlockedBySemaphoreWithoutTouchingOthers() {
        semaphore.tryAcquire(TARGET);

        CallAttempt attempt = service.send(REQUEST);

        assertThat(attempt).isEqualTo(new CallAttempt.BlockedBySemaphore());
        verifyNoInteractions(rateLimiter, client);
        verify(recorder).recordBlocked(TARGET);
        verifyNoMoreInteractions(recorder);
        verify(metrics).recordBlockedBySemaphore(TARGET);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    @DisplayName("토큰에서 막히면 BlockedByTokenBucket 을 돌려주고 외부를 호출하지 않는다")
    void returnsBlockedByTokenBucketWithoutCalling() {
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.reject(Duration.ofMillis(10)));

        CallAttempt attempt = service.send(REQUEST);

        assertThat(attempt).isEqualTo(new CallAttempt.BlockedByTokenBucket(Duration.ofMillis(10)));
        verifyNoInteractions(client);
        verify(recorder).recordBlocked(TARGET);
        verifyNoMoreInteractions(recorder);
        verify(metrics).recordBlockedByTokenBucket(TARGET);
        verifyNoMoreInteractions(metrics);
    }

    @Test
    @DisplayName("외부 호출이 끝나면 세마포어를 반납한다")
    void releasesAfterCall() {
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenReturn(RESULT);

        service.send(REQUEST);

        assertThat(semaphore.tryAcquire(TARGET)).isTrue();
        assertThat(semaphore.tryAcquire(TARGET)).isFalse();
    }

    @Test
    @DisplayName("토큰에서 막혀도 세마포어를 반납한다")
    void releasesWhenBlockedByTokenBucket() {
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.reject(Duration.ofMillis(10)));

        service.send(REQUEST);

        assertThat(semaphore.tryAcquire(TARGET)).isTrue();
        assertThat(semaphore.tryAcquire(TARGET)).isFalse();
    }

    @Test
    @DisplayName("외부 호출에서 예외가 나도 세마포어를 반납한다")
    void releasesWhenCallThrows() {
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenThrow(new IllegalStateException("예상하지 못한 예외"));

        assertThatThrownBy(() -> service.send(REQUEST))
                .isInstanceOf(IllegalStateException.class);

        assertThat(semaphore.tryAcquire(TARGET)).isTrue();
        assertThat(semaphore.tryAcquire(TARGET)).isFalse();
    }

    @Test
    @DisplayName("세마포어에서 막힌 경우에는 반납하지 않는다")
    void doesNotReleaseWhenBlockedBySemaphore() {
        semaphore.tryAcquire(TARGET);

        service.send(REQUEST);

        assertThat(semaphore.tryAcquire(TARGET)).isFalse();
    }

    @Test
    @DisplayName("외부가 실패로 답해도 호출은 한 것이므로 Called 를 돌려준다")
    void returnsCalledEvenWhenTargetFails() {
        OutboundResult failed =
                new OutboundResult(OutcomeKind.SERVER_ERROR, Duration.ofMillis(50), null, null, null, null);
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenReturn(failed);

        CallAttempt attempt = service.send(REQUEST);

        assertThat(attempt).isEqualTo(new CallAttempt.Called(failed));
        verify(recorder).record(TARGET, OutcomeKind.SERVER_ERROR, Duration.ofMillis(50));
        verify(metrics).recordCall(TARGET, OutcomeKind.SERVER_ERROR, Duration.ofMillis(50));
    }

    @Test
    @DisplayName("토큰 버킷에서 예외가 나도 세마포어를 반납하고 외부를 호출하지 않는다")
    void releasesWhenRateLimiterThrows() {
        when(rateLimiter.tryConsume(TARGET)).thenThrow(new IllegalStateException("예상하지 못한 예외"));

        assertThatThrownBy(() -> service.send(REQUEST))
                .isInstanceOf(IllegalStateException.class);

        assertThat(semaphore.tryAcquire(TARGET)).isTrue();
        assertThat(semaphore.tryAcquire(TARGET)).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("설정에 없는 대상이면 예외가 나고 토큰과 외부 호출을 건드리지 않는다 (현재 동작 기록)")
    void throwsForUnknownTarget() {
        OutboundRequest unknown = new OutboundRequest("req-2", "target-z", "{}", Instant.now());

        assertThatThrownBy(() -> service.send(unknown))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(rateLimiter, client, metrics);
    }

    @Test
    @DisplayName("외부를 호출할 때만 dry-run 에 호출 시작과 결과를 알린다")
    void notifiesDryRunAroundCall() {
        DryRunMonitor.Observation observation = mock(DryRunMonitor.Observation.class);
        when(dryRuns.beforeCall(TARGET)).thenReturn(observation);
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenReturn(RESULT);

        service.send(REQUEST);

        verify(observation).finish(RESULT);
    }

    @Test
    @DisplayName("외부 호출 중 예외가 나도 dry-run 에 호출이 끝났다고 알린다")
    void notifiesDryRunWhenCallFails() {
        DryRunMonitor.Observation observation = mock(DryRunMonitor.Observation.class);
        when(dryRuns.beforeCall(TARGET)).thenReturn(observation);
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.send(REQUEST)).isInstanceOf(IllegalStateException.class);

        verify(observation).finish(null);
    }

    @Test
    @DisplayName("제어 없음이면 세마포어와 토큰 버킷을 거치지 않고 바로 호출한다")
    void bypassesLimitsWithoutControl() {
        semaphore.tryAcquire(TARGET);
        when(client.call(REQUEST)).thenReturn(RESULT);

        CallAttempt attempt = service(ControlMode.NONE).send(REQUEST);

        assertThat(attempt).isEqualTo(new CallAttempt.Called(RESULT));
        verifyNoInteractions(rateLimiter);
        verify(recorder).record(TARGET, OutcomeKind.SUCCESS, Duration.ofMillis(120));
    }

    @Test
    @DisplayName("고정 한도도 세마포어와 토큰 버킷을 거친다")
    void fixedModeUsesLimits() {
        semaphore.tryAcquire(TARGET);

        CallAttempt attempt = service(ControlMode.FIXED).send(REQUEST);

        assertThat(attempt).isEqualTo(new CallAttempt.BlockedBySemaphore());
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("점검으로 본 응답은 판정 기록과 baseline 에 넣지 않고 뺀 횟수를 센다")
    void excludesMaintenanceResults() {
        OutboundResult maintenanceResult =
                new OutboundResult(OutcomeKind.UNDER_MAINTENANCE, Duration.ofMillis(30), null, null, 503, null);
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenReturn(maintenanceResult);
        when(maintenance.excludes(TARGET, OutcomeKind.UNDER_MAINTENANCE)).thenReturn(true);

        CallAttempt attempt = service.send(REQUEST);

        assertThat(attempt).isEqualTo(new CallAttempt.Called(maintenanceResult));
        verify(maintenance).onResult(TARGET, maintenanceResult);
        verify(metrics).recordCall(TARGET, OutcomeKind.UNDER_MAINTENANCE, Duration.ofMillis(30));
        verify(metrics).recordMaintenanceExcluded(TARGET);
        verifyNoMoreInteractions(recorder);
        verifyNoInteractions(baselines);
    }

    @Test
    @DisplayName("점검으로 본 응답은 dry-run 판정에도 넣지 않는다")
    void excludesMaintenanceResultsFromDryRun() {
        DryRunMonitor.Observation observation = mock(DryRunMonitor.Observation.class);
        when(dryRuns.beforeCall(TARGET)).thenReturn(observation);
        OutboundResult maintenanceResult =
                new OutboundResult(OutcomeKind.UNDER_MAINTENANCE, Duration.ofMillis(30), null, null, 503, null);
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenReturn(maintenanceResult);
        when(maintenance.excludes(TARGET, OutcomeKind.UNDER_MAINTENANCE)).thenReturn(true);

        service.send(REQUEST);

        verify(observation).finish(null);
    }

    @Test
    @DisplayName("정상 상태일 때의 응답만 baseline 학습에 넘긴다")
    void observesBaselineOnlyWhenHealthy() {
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenReturn(RESULT);

        service.send(REQUEST);
        verify(baselines).observe(TARGET, OutcomeKind.SUCCESS, Duration.ofMillis(120));

        when(evaluator.snapshotOf(TARGET)).thenReturn(HealthSnapshot.initial().withState(HealthState.DEGRADED));
        service.send(REQUEST);
        verifyNoMoreInteractions(baselines);
    }

    @Test
    @DisplayName("한도에 막히면 dry-run 에 알리지 않는다")
    void doesNotNotifyDryRunWhenBlocked() {
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.reject(Duration.ofMillis(10)));

        service.send(REQUEST);

        verifyNoInteractions(dryRuns);
    }
}
