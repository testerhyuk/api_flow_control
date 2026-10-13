package com.hyuk.flow_control.adapter.out.metrics;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.service.TargetSystemHealthEvaluator;
import com.hyuk.flow_control.application.service.TargetSystemLimitAdjuster;
import com.hyuk.flow_control.application.service.TargetSystemSemaphore;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        TargetSystemPolicyReader policyReader = mock(TargetSystemPolicyReader.class);
        evaluator = mock(TargetSystemHealthEvaluator.class);
        adjuster = mock(TargetSystemLimitAdjuster.class);
        semaphore = mock(TargetSystemSemaphore.class);
        HealthProperties healthProperties = new HealthProperties(
                Duration.ofSeconds(10), Duration.ofSeconds(1), 50, 0.10, 0.50, 2.0, Duration.ofSeconds(30));

        when(policyReader.readAll()).thenReturn(Map.of(TARGET, POLICY));
        when(evaluator.snapshotOf(TARGET)).thenReturn(HealthSnapshot.initial());

        new FlowControlGaugeRegistrar(registry, policyReader, evaluator, adjuster, semaphore, healthProperties);
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
        when(adjuster.rateOf(TARGET)).thenReturn(5.0);
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

    private double state(String state) {
        return registry.get("flow.control.health.state").tags("target", TARGET, "state", state).gauge().value();
    }

    private double gauge(String name) {
        return registry.get(name).tags("target", TARGET).gauge().value();
    }
}
