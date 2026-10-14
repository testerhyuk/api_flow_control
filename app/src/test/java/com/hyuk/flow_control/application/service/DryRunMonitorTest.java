package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.DryRunRepository;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.ResponseJudge;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.config.LimitAdjustmentProperties;
import com.hyuk.flow_control.domain.dryrun.DryRun;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DryRunMonitorTest {
    private static final String TARGET = "target-a";
    private static final ManagedPolicy BASE = Policies.policy(TARGET, 1);

    private DryRunRepository repository;
    private PolicyRegistry policies;
    private DryRunMonitor monitor;

    @BeforeEach
    void setUp() {
        repository = mock(DryRunRepository.class);
        policies = mock(PolicyRegistry.class);
        TargetSystemHealthEvaluator evaluator = mock(TargetSystemHealthEvaluator.class);
        TargetSystemLimitAdjuster adjuster = mock(TargetSystemLimitAdjuster.class);

        when(policies.policyOf(TARGET)).thenReturn(BASE);
        when(evaluator.statusOf(TARGET)).thenReturn(HealthStatus.initial());
        when(evaluator.snapshotOf(TARGET)).thenReturn(HealthSnapshot.initial());
        when(adjuster.rateOf(TARGET)).thenReturn(95.0);

        monitor = new DryRunMonitor(repository, policies, evaluator, adjuster, mock(ResponseJudge.class),
                mock(FlowControlMetrics.class),
                new HealthProperties(Duration.ofSeconds(10), Duration.ofSeconds(1), 50, 0.10, 0.50, 2.0,
                        Duration.ofSeconds(30)),
                new LimitAdjustmentProperties(0.5, 0.05));
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
    @DisplayName("다른 dry-run 의 결과는 돌려주지 않는다")
    void reportsOnlySameDryRun() {
        when(repository.loadAll()).thenReturn(List.of(dryRun(Instant.now().plus(Duration.ofMinutes(30)))));
        monitor.refresh();

        assertThat(monitor.reportOf(dryRun(Instant.now().plus(Duration.ofMinutes(10))))).isEmpty();
    }

    private static DryRun dryRun(Instant expiresAt) {
        ManagedPolicy candidate = Policies.withLimits(BASE.withVersion(2),
                new TargetSystemPolicy(80, Duration.ofMillis(150), 0.95, Duration.ofMillis(10)));
        return new DryRun(candidate, 1, expiresAt.minus(Duration.ofMinutes(30)), expiresAt, new ChangeNote("hyuk", "test"));
    }
}
