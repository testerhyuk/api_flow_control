package com.hyuk.flow_control.adapter.out.metrics;

import com.hyuk.flow_control.application.service.DryRunMonitor;
import com.hyuk.flow_control.application.service.PolicyRegistry;
import com.hyuk.flow_control.application.service.TargetSystemHealthEvaluator;
import com.hyuk.flow_control.application.service.TargetSystemLimitAdjuster;
import com.hyuk.flow_control.application.service.TargetSystemSemaphore;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.support.Policies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlowControlGaugeRegistrarTest {
    private static final String TARGET = "target-a";
    private static final TargetSystemPolicy POLICY =
            new TargetSystemPolicy(100, Duration.ofMillis(100), 0.95, Duration.ofMillis(10));

    private SimpleMeterRegistry registry;
    private TargetSystemHealthEvaluator evaluator;
    private TargetSystemLimitAdjuster adjuster;
    private TargetSystemSemaphore semaphore;
    private PolicyRegistry policies;
    private DryRunMonitor dryRuns;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        policies = mock(PolicyRegistry.class);
        dryRuns = mock(DryRunMonitor.class);
        evaluator = mock(TargetSystemHealthEvaluator.class);
        adjuster = mock(TargetSystemLimitAdjuster.class);
        semaphore = mock(TargetSystemSemaphore.class);
        HealthProperties healthProperties = new HealthProperties(
                Duration.ofSeconds(10), Duration.ofSeconds(1), 50, 0.10, 0.50, 2.0, Duration.ofSeconds(30));

        ManagedPolicy policy = Policies.withLimits(Policies.policy(TARGET, 3), POLICY);
        when(policies.readAll()).thenReturn(Map.of(TARGET, POLICY));
        when(policies.read(TARGET)).thenReturn(POLICY);
        when(policies.policyOf(TARGET)).thenReturn(policy);
        when(evaluator.snapshotOf(TARGET)).thenReturn(HealthSnapshot.initial());
        when(dryRuns.stateOf(TARGET)).thenReturn(Optional.empty());
        when(dryRuns.rateOf(TARGET)).thenReturn(Optional.empty());
        when(dryRuns.overlapOf(TARGET)).thenReturn(Optional.empty());
        when(adjuster.serversOf(TARGET)).thenReturn(1);
        when(dryRuns.concurrencyLimitOf(TARGET)).thenReturn(Optional.empty());

        new FlowControlGaugeRegistrar(registry, policies, evaluator, adjuster, semaphore, dryRuns, healthProperties);
    }

    @Test
    @DisplayName("지금 상태만 1 이고 나머지 상태는 0 이다")
    void exposesCurrentStateAsOne() {
        when(evaluator.snapshotOf(TARGET)).thenReturn(
                new HealthSnapshot(HealthState.CRITICAL, 100, 0.6, Duration.ofMillis(150), Instant.now()));

        assertThat(state("CRITICAL")).isEqualTo(1.0);
        assertThat(state("HEALTHY")).isZero();
        assertThat(state("DEGRADED")).isZero();
        assertThat(state("RECOVERING")).isZero();
    }

    @Test
    @DisplayName("값을 읽어 갈 때마다 최신 스냅샷을 읽는다")
    void readsLatestSnapshotEveryTime() {
        assertThat(state("HEALTHY")).isEqualTo(1.0);

        when(evaluator.snapshotOf(TARGET)).thenReturn(
                new HealthSnapshot(HealthState.DEGRADED, 100, 0.2, Duration.ofMillis(150), Instant.now()));

        assertThat(state("HEALTHY")).isZero();
        assertThat(state("DEGRADED")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("판정 근거로 나쁘다 비율, p95(초), 건수를 낸다")
    void exposesEvidence() {
        when(evaluator.snapshotOf(TARGET)).thenReturn(
                new HealthSnapshot(HealthState.DEGRADED, 120, 0.25, Duration.ofMillis(210), Instant.now()));

        assertThat(gauge("flow.control.health.failure.rate")).isEqualTo(0.25);
        assertThat(gauge("flow.control.health.p95")).isCloseTo(0.21, within(1e-9));
        assertThat(gauge("flow.control.health.samples")).isEqualTo(120.0);
    }

    @Test
    @DisplayName("p95 가 없으면 값이 없음(NaN)으로 낸다")
    void exposesNaNWithoutP95() {
        assertThat(gauge("flow.control.health.p95")).isNaN();
    }

    @Test
    @DisplayName("기준선으로 baseline-rtt, DEGRADED latency 기준, 처음 rate 를 낸다")
    void exposesBaselines() {
        assertThat(gauge("flow.control.health.baseline.rtt")).isCloseTo(0.1, within(1e-9));
        assertThat(gauge("flow.control.health.latency.threshold")).isCloseTo(0.2, within(1e-9));
        assertThat(gauge("flow.control.limit.rate.max")).isCloseTo(95.0, within(1e-9));
    }

    @Test
    @DisplayName("지금 rate, concurrency 한도, 진행 중인 요청 수를 낸다")
    void exposesLimits() {
        when(adjuster.appliedRateOf(TARGET)).thenReturn(5.0);
        when(semaphore.limitOf(TARGET)).thenReturn(1);
        when(semaphore.inFlightOf(TARGET)).thenReturn(3);

        assertThat(gauge("flow.control.limit.rate")).isEqualTo(5.0);
        assertThat(gauge("flow.control.limit.concurrency")).isEqualTo(1.0);
        assertThat(gauge("flow.control.inflight")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("시간 지표는 초 단위로 등록한다")
    void secondsUnit() {
        assertThat(registry.get("flow.control.health.p95").gauge().getId().getBaseUnit()).isEqualTo("seconds");
        assertThat(registry.get("flow.control.health.baseline.rtt").gauge().getId().getBaseUnit()).isEqualTo("seconds");
    }

    @Test
    @DisplayName("적용된 정책 버전을 낸다")
    void exposesPolicyVersion() {
        assertThat(gauge("flow.control.policy.version")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("기준값은 정책이 바뀌면 바뀐 값을 읽는다")
    void readsChangedPolicy() {
        when(policies.read(TARGET)).thenReturn(
                new TargetSystemPolicy(50, Duration.ofMillis(200), 0.9, Duration.ofMillis(10)));

        assertThat(gauge("flow.control.health.baseline.rtt")).isCloseTo(0.2, within(1e-9));
        assertThat(gauge("flow.control.limit.rate.max")).isCloseTo(45.0, within(1e-9));
    }

    @Test
    @DisplayName("dry-run 이 없으면 dry-run 지표는 0 이거나 값이 없음(NaN)이다")
    void exposesNoDryRun() {
        assertThat(gauge("flow.control.dryrun.active")).isZero();
        assertThat(dryRunState("HEALTHY")).isNaN();
        assertThat(gauge("flow.control.dryrun.limit.rate")).isNaN();
        assertThat(gauge("flow.control.dryrun.limit.concurrency")).isNaN();
    }

    @Test
    @DisplayName("dry-run 중이면 새 값 기준 상태, rate, concurrency 를 낸다")
    void exposesDryRun() {
        when(dryRuns.isRunning(TARGET)).thenReturn(true);
        when(dryRuns.stateOf(TARGET)).thenReturn(Optional.of(HealthState.DEGRADED));
        when(dryRuns.rateOf(TARGET)).thenReturn(Optional.of(38.0));
        when(dryRuns.concurrencyLimitOf(TARGET)).thenReturn(Optional.of(4));

        assertThat(gauge("flow.control.dryrun.active")).isEqualTo(1.0);
        assertThat(dryRunState("DEGRADED")).isEqualTo(1.0);
        assertThat(dryRunState("HEALTHY")).isZero();
        assertThat(gauge("flow.control.dryrun.limit.rate")).isEqualTo(38.0);
        assertThat(gauge("flow.control.dryrun.limit.concurrency")).isEqualTo(4.0);
    }

    @Test
    @DisplayName("적용된 rate, 이 서버가 원하는 rate, 원하는 rate 를 남긴 서버 수를 낸다")
    void exposesSharedLimit() {
        when(adjuster.appliedRateOf(TARGET)).thenReturn(47.5);
        when(adjuster.rateOf(TARGET)).thenReturn(95.0);
        when(adjuster.serversOf(TARGET)).thenReturn(2);

        assertThat(gauge("flow.control.limit.rate")).isEqualTo(47.5);
        assertThat(gauge("flow.control.limit.rate.proposed")).isEqualTo(95.0);
        assertThat(gauge("flow.control.limit.servers")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("dry-run 중 다른 서버와 겹친 시간을 초 단위로 낸다")
    void exposesDryRunOverlap() {
        assertThat(gauge("flow.control.dryrun.overlap")).isNaN();

        when(dryRuns.overlapOf(TARGET)).thenReturn(Optional.of(Duration.ofSeconds(40)));

        assertThat(gauge("flow.control.dryrun.overlap")).isEqualTo(40.0);
    }

    private double dryRunState(String state) {
        return registry.get("flow.control.dryrun.health.state").tags("target", TARGET, "state", state).gauge().value();
    }

    private double state(String state) {
        return registry.get("flow.control.health.state").tags("target", TARGET, "state", state).gauge().value();
    }

    private double gauge(String name) {
        return registry.get(name).tags("target", TARGET).gauge().value();
    }
}
