package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.DryRunRepository;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.ResponseJudge;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.config.LimitAdjustmentProperties;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunEnd;
import com.hyuk.flow_control.domain.dryrun.DryRunResult;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.HealthStatus;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DryRunMonitorTest {
    private static final String TARGET = "target-a";
    private static final ManagedPolicy BASE = Policies.policy(TARGET, 1);

    private DryRunRepository repository;
    private PolicyRegistry policies;
    private TargetSystemLimitAdjuster adjuster;
    private DryRunMonitor monitor;

    @BeforeEach
    void setUp() {
        repository = mock(DryRunRepository.class);
        policies = mock(PolicyRegistry.class);
        TargetSystemHealthEvaluator evaluator = mock(TargetSystemHealthEvaluator.class);
        adjuster = mock(TargetSystemLimitAdjuster.class);

        when(policies.policyOf(TARGET)).thenReturn(BASE);
        when(evaluator.statusOf(TARGET)).thenReturn(HealthStatus.initial());
        when(evaluator.snapshotOf(TARGET)).thenReturn(HealthSnapshot.initial());
        when(adjuster.appliedRateOf(TARGET)).thenReturn(95.0);
        when(adjuster.serversOf(TARGET)).thenReturn(1);
        BaselineEstimator baselines = mock(BaselineEstimator.class);
        when(baselines.effective(eq(TARGET), any(TargetSystemPolicy.class)))
                .thenAnswer(invocation -> BaselineEstimator.Baselines.of(invocation.getArgument(1)));

        monitor = new DryRunMonitor(repository, policies, evaluator, adjuster, baselines,
                mock(MaintenanceMonitor.class), mock(ResponseJudge.class),
                mock(FlowControlMetrics.class),
                new HealthProperties(Duration.ofSeconds(10), Duration.ofSeconds(1), 50, 0.10, 0.50, 2.0,
                        Duration.ofSeconds(30)),
                new LimitAdjustmentProperties(0.5));
    }

    @Test
    @DisplayName("DB 에 있는 dry-run 을 읽어 시작한다")
    void startsStoredDryRun() {
        DryRun dryRun = dryRun(Instant.now().plus(Duration.ofMinutes(30)));
        when(repository.loadAll()).thenReturn(List.of(dryRun));

        monitor.refresh();

        assertThat(monitor.isRunning(TARGET)).isTrue();
        assertThat(monitor.stateOf(TARGET)).contains(HealthState.HEALTHY);
        assertThat(monitor.rateOf(TARGET).orElseThrow()).isCloseTo(76.0, within(1e-9));
        assertThat(monitor.reportOf(dryRun)).isPresent();
    }

    @Test
    @DisplayName("이 서버의 정책 버전이 dry-run 기준 버전과 다르면 시작하지 않는다")
    void doesNotStartWithDifferentVersion() {
        when(policies.policyOf(TARGET)).thenReturn(BASE.withVersion(2));
        when(repository.loadAll()).thenReturn(List.of(dryRun(Instant.now().plus(Duration.ofMinutes(30)))));

        monitor.refresh();

        assertThat(monitor.isRunning(TARGET)).isFalse();
    }

    @Test
    @DisplayName("끝 시각이 지난 dry-run 은 시작하지 않는다")
    void doesNotStartExpired() {
        when(repository.loadAll()).thenReturn(List.of(dryRun(Instant.now().minusSeconds(1))));

        monitor.refresh();

        assertThat(monitor.isRunning(TARGET)).isFalse();
    }

    @Test
    @DisplayName("DB 에서 사라진 dry-run 은 멈춘다")
    void stopsRemovedDryRun() {
        when(repository.loadAll()).thenReturn(List.of(dryRun(Instant.now().plus(Duration.ofMinutes(30)))));
        monitor.refresh();
        when(repository.loadAll()).thenReturn(List.of());

        monitor.refresh();

        assertThat(monitor.isRunning(TARGET)).isFalse();
        assertThat(monitor.stateOf(TARGET)).isEmpty();
    }

    @Test
    @DisplayName("정책 버전이 바뀌면 dry-run 을 멈춘다")
    void stopsWhenPolicyChanges() {
        when(repository.loadAll()).thenReturn(List.of(dryRun(Instant.now().plus(Duration.ofMinutes(30)))));
        monitor.refresh();
        when(policies.policyOf(TARGET)).thenReturn(BASE.withVersion(2));

        monitor.evaluateAll();

        assertThat(monitor.isRunning(TARGET)).isFalse();
    }

    @Test
    @DisplayName("다시 읽을 때 이 서버가 지운 dry-run 은 이 서버의 결과와 끝난 이유를 남긴다")
    void recordsResultOfDeletedDryRun() {
        DryRun running = dryRun(Instant.now().plus(Duration.ofMinutes(30)));
        when(repository.loadAll()).thenReturn(List.of(running));
        monitor.refresh();
        DryRun expired = dryRun(Instant.now().minusSeconds(1));
        when(repository.deleteStale(any())).thenReturn(List.of(running), List.of(expired));
        when(repository.loadAll()).thenReturn(List.of());

        monitor.refresh();
        monitor.refresh();

        ArgumentCaptor<DryRunResult> results = ArgumentCaptor.forClass(DryRunResult.class);
        verify(repository, times(2)).saveResult(results.capture());
        assertThat(results.getAllValues().get(0).end()).isEqualTo(DryRunEnd.POLICY_CHANGED);
        assertThat(results.getAllValues().get(0).report()).isNotNull();
        assertThat(results.getAllValues().get(1).end()).isEqualTo(DryRunEnd.EXPIRED);
        assertThat(results.getAllValues().get(1).report()).isNull();
        assertThat(monitor.isRunning(TARGET)).isFalse();
    }

    @Test
    @DisplayName("판정 주기가 만료를 먼저 봐서 멈춘 뒤 다시 읽어도 마지막 결과를 남긴다")
    void recordsReportWhenTickStopsFirst() throws InterruptedException {
        DryRun dryRun = startExpiringWithOneCall();
        monitor.evaluateAll();
        assertThat(monitor.isRunning(TARGET)).isFalse();
        when(repository.deleteStale(any())).thenReturn(List.of(dryRun));
        when(repository.loadAll()).thenReturn(List.of());

        monitor.refresh();

        DryRunResult result = savedResult();
        assertThat(result.end()).isEqualTo(DryRunEnd.EXPIRED);
        assertThat(result.report()).isNotNull();
        assertThat(result.report().observedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("멈춘 뒤에 들어온 호출은 마지막 결과에 세지 않는다")
    void doesNotCountCallsAfterStop() throws InterruptedException {
        DryRun dryRun = startExpiringWithOneCall();
        monitor.evaluateAll();

        monitor.beforeCall(TARGET)
                .finish(new OutboundResult(OutcomeKind.SUCCESS, Duration.ofMillis(10), null, null, 200, null));

        assertThat(monitor.reportOf(dryRun).orElseThrow().observedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("DB 장애 중에 끝나도 DB 가 돌아오면 마지막 결과를 남긴다")
    void recordsReportAfterDatabaseRecovers() throws InterruptedException {
        DryRun dryRun = startExpiringWithOneCall();
        when(repository.deleteStale(any())).thenThrow(new IllegalStateException("db down"));
        monitor.refresh();
        assertThat(monitor.isRunning(TARGET)).isFalse();

        doReturn(List.of(dryRun)).when(repository).deleteStale(any());
        when(repository.loadAll()).thenReturn(List.of());
        monitor.refresh();

        assertThat(savedResult().report()).isNotNull();
    }

    @Test
    @DisplayName("저장을 마친 보관본은 지운다")
    void removesReportAfterSaving() throws InterruptedException {
        DryRun dryRun = startExpiringWithOneCall();
        monitor.evaluateAll();
        when(repository.deleteStale(any())).thenReturn(List.of(dryRun));
        when(repository.loadAll()).thenReturn(List.of());

        monitor.refresh();

        assertThat(monitor.reportOf(dryRun)).isEmpty();
    }

    @Test
    @DisplayName("다른 서버가 DB 에서 먼저 지운 dry-run 의 보관본도 지운다")
    void removesReportDeletedByOtherServer() throws InterruptedException {
        DryRun dryRun = startExpiringWithOneCall();
        monitor.evaluateAll();
        when(repository.loadAll()).thenReturn(List.of());

        monitor.refresh();

        assertThat(monitor.reportOf(dryRun)).isEmpty();
        verify(repository, never()).saveResult(any());
    }

    @Test
    @DisplayName("DB 에 아직 남아 있는 dry-run 의 보관본은 지우지 않는다")
    void keepsReportWhileStored() throws InterruptedException {
        DryRun dryRun = startExpiringWithOneCall();
        monitor.evaluateAll();

        monitor.refresh();

        assertThat(monitor.reportOf(dryRun)).isPresent();
    }

    @Test
    @DisplayName("DB 를 읽지 못해도 진행 중인 dry-run 은 계속한다")
    void keepsRunningWhenDatabaseFails() {
        when(repository.loadAll()).thenReturn(List.of(dryRun(Instant.now().plus(Duration.ofMinutes(30)))));
        monitor.refresh();
        when(repository.loadAll()).thenThrow(new IllegalStateException("db down"));

        monitor.refresh();

        assertThat(monitor.isRunning(TARGET)).isTrue();
    }

    @Test
    @DisplayName("dry-run 이 없으면 호출을 세지 않는다")
    void ignoresCallsWithoutDryRun() {
        DryRunMonitor.Observation observation = monitor.beforeCall(TARGET);

        observation.finish(new OutboundResult(OutcomeKind.SUCCESS, Duration.ofMillis(10), null, null, 200, null));

        assertThat(observation).isSameAs(DryRunMonitor.Observation.NONE);
    }

    @Test
    @DisplayName("dry-run 중이면 호출을 세고 결과를 남긴다")
    void observesCallsWhileRunning() {
        DryRun dryRun = dryRun(Instant.now().plus(Duration.ofMinutes(30)));
        when(repository.loadAll()).thenReturn(List.of(dryRun));
        monitor.refresh();

        monitor.beforeCall(TARGET)
                .finish(new OutboundResult(OutcomeKind.SUCCESS, Duration.ofMillis(10), null, null, 200, null));
        monitor.recordWaited(TARGET);

        var report = monitor.reportOf(dryRun).orElseThrow();
        assertThat(report.observedCalls()).isEqualTo(1);
        assertThat(report.limits().waitedByCurrentLimit()).isEqualTo(1);
    }

    @Test
    @DisplayName("진행 중인 dry-run 의 판정에 쓴 호출 수를 돌려주고, 끝나면 비고, 새 dry-run 은 0 에서 시작한다")
    void exposesJudgedCallsOfRunningDryRun() {
        assertThat(monitor.judgedCallsOf(TARGET)).isEmpty();

        when(repository.loadAll()).thenReturn(List.of(dryRun(Instant.now().plus(Duration.ofMinutes(30)))));
        monitor.refresh();
        assertThat(monitor.judgedCallsOf(TARGET)).contains(0L);

        monitor.beforeCall(TARGET)
                .finish(new OutboundResult(OutcomeKind.SUCCESS, Duration.ofMillis(10), null, null, 200, null));
        assertThat(monitor.judgedCallsOf(TARGET)).contains(1L);

        when(repository.loadAll()).thenReturn(List.of());
        monitor.refresh();
        assertThat(monitor.judgedCallsOf(TARGET)).isEmpty();

        when(repository.loadAll()).thenReturn(List.of(dryRun(Instant.now().plus(Duration.ofMinutes(20)))));
        monitor.refresh();
        assertThat(monitor.judgedCallsOf(TARGET)).contains(0L);
    }

    @Test
    @DisplayName("다른 dry-run 의 결과는 돌려주지 않는다")
    void reportsOnlySameDryRun() {
        when(repository.loadAll()).thenReturn(List.of(dryRun(Instant.now().plus(Duration.ofMinutes(30)))));
        monitor.refresh();

        assertThat(monitor.reportOf(dryRun(Instant.now().plus(Duration.ofMinutes(10))))).isEmpty();
    }

    @Test
    @DisplayName("판정할 때 다른 서버가 있으면 겹친 시간을 쌓는다")
    void tracksOverlapWithOtherServers() throws InterruptedException {
        when(repository.loadAll()).thenReturn(List.of(dryRun(Instant.now().plus(Duration.ofMinutes(30)))));
        monitor.refresh();
        when(adjuster.serversOf(TARGET)).thenReturn(2);

        Thread.sleep(20);
        monitor.evaluateAll();

        assertThat(monitor.overlapOf(TARGET).orElseThrow()).isPositive();
    }

    @Test
    @DisplayName("dry-run 은 공용 버킷에 실제로 적용된 rate 에서 시작한다")
    void startsFromAppliedRate() {
        when(adjuster.appliedRateOf(TARGET)).thenReturn(47.5);
        when(repository.loadAll()).thenReturn(List.of(dryRun(Instant.now().plus(Duration.ofMinutes(30)))));

        monitor.refresh();

        assertThat(monitor.rateOf(TARGET).orElseThrow()).isCloseTo(47.5, within(1e-9));
    }

    private DryRun startExpiringWithOneCall() throws InterruptedException {
        DryRun dryRun = dryRun(Instant.now().plusMillis(200));
        when(repository.loadAll()).thenReturn(List.of(dryRun));
        monitor.refresh();
        monitor.beforeCall(TARGET)
                .finish(new OutboundResult(OutcomeKind.SUCCESS, Duration.ofMillis(10), null, null, 200, null));
        Thread.sleep(250);
        return dryRun;
    }

    private DryRunResult savedResult() {
        ArgumentCaptor<DryRunResult> result = ArgumentCaptor.forClass(DryRunResult.class);
        verify(repository).saveResult(result.capture());
        return result.getValue();
    }

    private static DryRun dryRun(Instant expiresAt) {
        ManagedPolicy candidate = Policies.withLimits(BASE.withVersion(2),
                new TargetSystemPolicy(80, Duration.ofMillis(150), 0.95, Duration.ofMillis(10)));
        return new DryRun(candidate, 1, expiresAt.minus(Duration.ofMinutes(30)), expiresAt, new ChangeNote("hyuk", "test"));
    }
}
