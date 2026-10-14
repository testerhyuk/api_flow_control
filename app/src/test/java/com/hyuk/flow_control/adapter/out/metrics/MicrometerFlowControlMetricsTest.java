package com.hyuk.flow_control.adapter.out.metrics;

import com.hyuk.flow_control.domain.expiry.ExpiryPoint;
import com.hyuk.flow_control.domain.health.HealthMode;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.LowTrafficEvent;
import com.hyuk.flow_control.domain.health.SuppressedBy;
import com.hyuk.flow_control.domain.limit.LimitStore;
import com.hyuk.flow_control.domain.maintenance.MaintenanceSource;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.retry.DeadLetterReason;
import com.hyuk.flow_control.domain.retry.RetryStage;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerFlowControlMetricsTest {
    private SimpleMeterRegistry registry;
    private MicrometerFlowControlMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerFlowControlMetrics(registry);
    }

    @Test
    @DisplayName("호출 결과를 대상과 kind 별로 센다")
    void countsCallsByTargetAndKind() {
        metrics.recordCall("target-a", OutcomeKind.SUCCESS, Duration.ofMillis(120));
        metrics.recordCall("target-a", OutcomeKind.SUCCESS, Duration.ofMillis(80));
        metrics.recordCall("target-a", OutcomeKind.TIMEOUT, Duration.ofSeconds(3));
        metrics.recordCall("target-b", OutcomeKind.SUCCESS, Duration.ofMillis(300));

        assertThat(callCount("target-a", "SUCCESS")).isEqualTo(2.0);
        assertThat(callCount("target-a", "TIMEOUT")).isEqualTo(1.0);
        assertThat(callCount("target-b", "SUCCESS")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("latency 를 대상과 kind 별로 기록한다")
    void recordsLatency() {
        metrics.recordCall("target-a", OutcomeKind.SUCCESS, Duration.ofMillis(120));
        metrics.recordCall("target-a", OutcomeKind.SUCCESS, Duration.ofMillis(80));

        Timer timer = latencyTimer("target-a", "SUCCESS");

        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(200.0);
    }

    @Test
    @DisplayName("세마포어와 토큰 버킷에서 막힌 시도를 이유별로 센다")
    void countsBlockedByReason() {
        metrics.recordBlockedBySemaphore("target-a");
        metrics.recordBlockedByTokenBucket("target-a");
        metrics.recordBlockedByTokenBucket("target-a");

        assertThat(blockedCount("target-a", "semaphore")).isEqualTo(1.0);
        assertThat(blockedCount("target-a", "token_bucket")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("상태가 바뀐 횟수를 바뀌기 전과 후 상태별로 센다")
    void countsStateTransitions() {
        metrics.recordStateTransition("target-a", HealthState.HEALTHY, HealthState.CRITICAL);
        metrics.recordStateTransition("target-a", HealthState.CRITICAL, HealthState.RECOVERING);
        metrics.recordStateTransition("target-a", HealthState.HEALTHY, HealthState.CRITICAL);

        assertThat(registry.get("flow.control.health.transitions")
                .tags("target", "target-a", "from", "HEALTHY", "to", "CRITICAL")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get("flow.control.health.transitions")
                .tags("target", "target-a", "from", "CRITICAL", "to", "RECOVERING")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("한도 대기 시간을 대상별로 기록한다")
    void recordsIntakeWait() {
        metrics.recordIntakeWait("target-a", Duration.ofMillis(250));
        metrics.recordIntakeWait("target-a", Duration.ZERO);

        Timer timer = registry.get("flow.control.intake.wait").tags("target", "target-a").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250.0);
    }

    @Test
    @DisplayName("재시도 예약을 대상, 단계, kind 별로 센다")
    void countsRetryScheduled() {
        metrics.recordRetryScheduled("target-a", RetryStage.FIRST, OutcomeKind.TIMEOUT);
        metrics.recordRetryScheduled("target-a", RetryStage.FIRST, OutcomeKind.TIMEOUT);
        metrics.recordRetryScheduled("target-a", RetryStage.THIRD, OutcomeKind.TOO_MANY_REQUESTS);

        assertThat(registry.get("flow.control.retry.scheduled")
                .tags("target", "target-a", "stage", "1s", "kind", "TIMEOUT").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("flow.control.retry.scheduled")
                .tags("target", "target-a", "stage", "60s", "kind", "TOO_MANY_REQUESTS").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("재시도 성공을 대상과 단계별로 센다")
    void countsRetrySucceeded() {
        metrics.recordRetrySucceeded("target-a", RetryStage.SECOND);

        assertThat(registry.get("flow.control.retry.succeeded")
                .tags("target", "target-a", "stage", "10s").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("DLQ 전송을 대상과 이유별로 센다")
    void countsDeadLetters() {
        metrics.recordDeadLetter("target-a", DeadLetterReason.RETRY_EXHAUSTED);
        metrics.recordDeadLetter("target-a", DeadLetterReason.INVALID_MESSAGE);
        metrics.recordDeadLetter("target-a", DeadLetterReason.INVALID_MESSAGE);

        assertThat(registry.get("flow.control.dlq.sent")
                .tags("target", "target-a", "reason", "retry_exhausted").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("flow.control.dlq.sent")
                .tags("target", "target-a", "reason", "invalid_message").counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("정책을 다시 읽지 못한 횟수와 잘못된 정책 수를 센다")
    void countsPolicyProblems() {
        metrics.recordPolicyRefreshFailure();
        metrics.recordInvalidPolicy("target-a");

        assertThat(registry.get("flow.control.policy.refresh.failures").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("flow.control.policy.invalid").tags("target", "target-a").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("dry-run 에서 본 호출, 막혔을 호출, 기다린 호출을 센다")
    void countsDryRunCalls() {
        metrics.recordDryRunObserved("target-a");
        metrics.recordDryRunObserved("target-a");
        metrics.recordDryRunBlockedBySemaphore("target-a");
        metrics.recordDryRunBlockedByTokenBucket("target-a");
        metrics.recordDryRunWaited("target-a");

        assertThat(dryRunCount("flow.control.dryrun.observed")).isEqualTo(2.0);
        assertThat(registry.get("flow.control.dryrun.would.block")
                .tags("target", "target-a", "reason", "semaphore").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("flow.control.dryrun.would.block")
                .tags("target", "target-a", "reason", "token_bucket").counter().count()).isEqualTo(1.0);
        assertThat(dryRunCount("flow.control.dryrun.waited")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("dry-run 의 판단 차이, timeout, 상태 변경을 센다")
    void countsDryRunJudgments() {
        metrics.recordDryRunJudgmentChange("target-a", OutcomeKind.BUSINESS_REJECTED, OutcomeKind.SERVER_ERROR);
        metrics.recordDryRunTimeout("target-a");
        metrics.recordDryRunUnknownTimeout("target-a");
        metrics.recordDryRunStateTransition("target-a", HealthState.HEALTHY, HealthState.DEGRADED);

        assertThat(registry.get("flow.control.dryrun.judgment.changes")
                .tags("target", "target-a", "actual", "BUSINESS_REJECTED", "candidate", "SERVER_ERROR")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("flow.control.dryrun.timeouts")
                .tags("target", "target-a", "result", "would_timeout").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("flow.control.dryrun.timeouts")
                .tags("target", "target-a", "result", "unknown").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("flow.control.dryrun.health.transitions")
                .tags("target", "target-a", "from", "HEALTHY", "to", "DEGRADED").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("공용 버킷과 서버 안 버킷 사이를 바꾼 횟수를 방향별로 센다")
    void countsLimitStoreSwitches() {
        metrics.recordLimitStoreSwitch(LimitStore.LOCAL);
        metrics.recordLimitStoreSwitch(LimitStore.SHARED);
        metrics.recordLimitStoreSwitch(LimitStore.SHARED);

        assertThat(registry.get("flow.control.limit.store.switches").tags("to", "local").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("flow.control.limit.store.switches").tags("to", "shared").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("Redis 왕복 시간을 기록한다")
    void recordsRedisLatency() {
        metrics.recordRedisLatency(Duration.ofNanos(300_000));
        metrics.recordRedisLatency(Duration.ofMillis(2));

        Timer timer = registry.get("flow.control.redis.latency").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(2_300_000.0);
    }

    @Test
    @DisplayName("Redis 오류를 작업과 이유(timeout, failure)별로 센다")
    void countsRedisErrors() {
        metrics.recordRedisError("token", true);
        metrics.recordRedisError("proposal", false);

        assertThat(registry.get("flow.control.redis.errors").tags("operation", "token", "reason", "timeout")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("flow.control.redis.errors").tags("operation", "proposal", "reason", "failure")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("진동 방지가 막은 횟수를 이유별로 센다")
    void countsSuppressed() {
        metrics.recordSuppressed("target-a", SuppressedBy.HYSTERESIS);
        metrics.recordSuppressed("target-a", SuppressedBy.CONSECUTIVE);
        metrics.recordSuppressed("target-a", SuppressedBy.CONSECUTIVE);

        assertThat(registry.get("flow.control.health.suppressed").tags("target", "target-a", "reason", "hysteresis")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("flow.control.health.suppressed").tags("target", "target-a", "reason", "consecutive")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("판정 모드 전환과 저트래픽 사건을 센다")
    void countsLowTraffic() {
        metrics.recordModeTransition("target-a", HealthMode.LOW_TRAFFIC);
        metrics.recordLowTrafficEvent("target-a", LowTrafficEvent.TOO_MANY_REQUESTS);

        assertThat(registry.get("flow.control.health.mode.transitions").tags("target", "target-a", "to", "low_traffic")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("flow.control.health.low.traffic.events")
                .tags("target", "target-a", "event", "too_many_requests").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("점검 시작 횟수와 판정에서 뺀 응답 수를 센다")
    void countsMaintenance() {
        metrics.recordMaintenanceStarted("target-a", MaintenanceSource.SCHEDULE);
        metrics.recordMaintenanceExcluded("target-a");
        metrics.recordMaintenanceExcluded("target-a");

        assertThat(registry.get("flow.control.maintenance.started").tags("target", "target-a", "source", "schedule")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("flow.control.maintenance.excluded").tags("target", "target-a")
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("만료 횟수를 어디서 만료됐는지별로 세고, 꺼낸 요청의 나이를 기록한다")
    void recordsExpiry() {
        metrics.recordExpired("target-a", ExpiryPoint.RETRY_SCHEDULE);
        metrics.recordRequestAge("target-a", Duration.ofSeconds(30));

        assertThat(registry.get("flow.control.expired").tags("target", "target-a", "where", "retry_schedule")
                .counter().count()).isEqualTo(1.0);
        Timer age = registry.get("flow.control.request.age").tags("target", "target-a").timer();
        assertThat(age.count()).isEqualTo(1);
        assertThat(age.totalTime(TimeUnit.SECONDS)).isEqualTo(30.0);
    }

    private double dryRunCount(String name) {
        return registry.get(name).tags("target", "target-a").counter().count();
    }

    private double callCount(String target, String kind) {
        return registry.get("flow.control.outbound.calls").tags("target", target, "kind", kind).counter().count();
    }

    private Timer latencyTimer(String target, String kind) {
        return registry.get("flow.control.outbound.latency").tags("target", target, "kind", kind).timer();
    }

    private double blockedCount(String target, String reason) {
        return registry.get("flow.control.outbound.blocked.attempts").tags("target", target, "reason", reason).counter().count();
    }
}
