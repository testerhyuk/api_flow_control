package com.hyuk.flow_control.adapter.out.metrics;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MicrometerFlowControlMetrics implements FlowControlMetrics {
    private static final Duration OUTBOUND_LATENCY_MIN = Duration.ofMillis(1);
    private static final Duration OUTBOUND_LATENCY_MAX = Duration.ofSeconds(10);
    private static final Duration INTAKE_WAIT_MIN = Duration.ofMillis(1);
    private static final Duration INTAKE_WAIT_MAX = Duration.ofMinutes(10);
    private static final Duration REQUEST_AGE_MIN = Duration.ofMillis(100);
    private static final Duration REQUEST_AGE_MAX = Duration.ofMinutes(10);
    private static final Duration REDIS_LATENCY_MIN = Duration.ofNanos(100_000);
    private static final Duration REDIS_LATENCY_MAX = Duration.ofMillis(50);

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> latestPolicyInvalid = new ConcurrentHashMap<>();

    public MicrometerFlowControlMetrics(MeterRegistry registry) {
        this.registry = registry;
        policyRefreshFailures();
        for (LimitStore store : LimitStore.values()) {
            limitStoreSwitches(store);
        }
    }

    // 누적 수 지표는 첫 사건 때 만들어지면 그 1건이 increase 에 잡히지 않는다. 그래서 조회 기간 횟수를 보여주는 지표는 0으로 미리 만든다.
    @Override
    public void registerTarget(String targetSystemId, List<String> policyListeners) {
        for (HealthState from : HealthState.values()) {
            for (HealthState to : HealthState.values()) {
                if (from != to) {
                    stateTransitions(targetSystemId, from, to);
                }
            }
        }
        for (SuppressedBy reason : SuppressedBy.values()) {
            suppressed(targetSystemId, reason);
        }
        for (HealthMode mode : HealthMode.values()) {
            modeTransitions(targetSystemId, mode);
        }
        for (LowTrafficEvent event : LowTrafficEvent.values()) {
            lowTrafficEvents(targetSystemId, event);
        }
        for (MaintenanceSource source : MaintenanceSource.values()) {
            maintenanceStarted(targetSystemId, source);
        }
        maintenanceExcluded(targetSystemId);
        for (String listener : policyListeners) {
            policyApplyFailures(targetSystemId, listener);
        }
    }

    @Override
    public void recordCall(String targetSystemId, OutcomeKind kind, Duration latency) {
        Counter.builder("flow.control.outbound.calls")
                .tag("target", targetSystemId)
                .tag("kind", kind.name())
                .register(registry)
                .increment();

        Timer.builder("flow.control.outbound.latency")
                .tag("target", targetSystemId)
                .tag("kind", kind.name())
                .publishPercentileHistogram()
                .minimumExpectedValue(OUTBOUND_LATENCY_MIN)
                .maximumExpectedValue(OUTBOUND_LATENCY_MAX)
                .register(registry)
                .record(latency);
    }

    @Override
    public void recordBlockedBySemaphore(String targetSystemId) {
        recordBlocked(targetSystemId, "semaphore");
    }

    @Override
    public void recordBlockedByTokenBucket(String targetSystemId) {
        recordBlocked(targetSystemId, "token_bucket");
    }

    @Override
    public void recordStateTransition(String targetSystemId, HealthState from, HealthState to) {
        stateTransitions(targetSystemId, from, to).increment();
    }

    private Counter stateTransitions(String targetSystemId, HealthState from, HealthState to) {
        return Counter.builder("flow.control.health.transitions")
                .tag("target", targetSystemId)
                .tag("from", from.name())
                .tag("to", to.name())
                .register(registry);
    }

    @Override
    public void recordIntakeWait(String targetSystemId, Duration waited) {
        Timer.builder("flow.control.intake.wait")
                .tag("target", targetSystemId)
                .publishPercentileHistogram()
                .minimumExpectedValue(INTAKE_WAIT_MIN)
                .maximumExpectedValue(INTAKE_WAIT_MAX)
                .register(registry)
                .record(waited);
    }

    @Override
    public void recordRetryScheduled(String targetSystemId, RetryStage stage, OutcomeKind kind) {
        Counter.builder("flow.control.retry.scheduled")
                .tag("target", targetSystemId)
                .tag("stage", stage.label())
                .tag("kind", kind.name())
                .register(registry)
                .increment();
    }

    @Override
    public void recordRetrySucceeded(String targetSystemId, RetryStage stage) {
        Counter.builder("flow.control.retry.succeeded")
                .tag("target", targetSystemId)
                .tag("stage", stage.label())
                .register(registry)
                .increment();
    }

    @Override
    public void recordDeadLetter(String targetSystemId, DeadLetterReason reason) {
        Counter.builder("flow.control.dlq.sent")
                .tag("target", targetSystemId)
                .tag("reason", reason.label())
                .register(registry)
                .increment();
    }

    @Override
    public void recordPolicyRefreshFailure() {
        policyRefreshFailures().increment();
    }

    private Counter policyRefreshFailures() {
        return Counter.builder("flow.control.policy.refresh.failures").register(registry);
    }

    @Override
    public void recordInvalidPolicy(String targetSystemId) {
        Counter.builder("flow.control.policy.invalid")
                .tag("target", targetSystemId)
                .register(registry)
                .increment();
    }

    @Override
    public void recordPolicyApplyFailure(String targetSystemId, String listener) {
        policyApplyFailures(targetSystemId, listener).increment();
    }

    private Counter policyApplyFailures(String targetSystemId, String listener) {
        return Counter.builder("flow.control.policy.apply.failures")
                .tag("target", targetSystemId)
                .tag("listener", listener)
                .register(registry);
    }

    @Override
    public void recordLatestPolicyInvalid(String targetSystemId, boolean invalid) {
        latestPolicyInvalid.computeIfAbsent(targetSystemId, id -> {
            AtomicInteger value = new AtomicInteger();
            Gauge.builder("flow.control.policy.invalid.latest", value, AtomicInteger::get)
                    .tag("target", id)
                    .register(registry);
            return value;
        }).set(invalid ? 1 : 0);
    }

    @Override
    public void recordDryRunObserved(String targetSystemId) {
        dryRunCounter("flow.control.dryrun.observed", targetSystemId).register(registry).increment();
    }

    @Override
    public void recordDryRunBlockedBySemaphore(String targetSystemId) {
        dryRunCounter("flow.control.dryrun.would.block", targetSystemId)
                .tag("reason", "semaphore")
                .register(registry)
                .increment();
    }

    @Override
    public void recordDryRunBlockedByTokenBucket(String targetSystemId) {
        dryRunCounter("flow.control.dryrun.would.block", targetSystemId)
                .tag("reason", "token_bucket")
                .register(registry)
                .increment();
    }

    @Override
    public void recordDryRunWaited(String targetSystemId) {
        dryRunCounter("flow.control.dryrun.waited", targetSystemId).register(registry).increment();
    }

    @Override
    public void recordDryRunJudgmentChange(String targetSystemId, OutcomeKind actual, OutcomeKind candidate) {
        dryRunCounter("flow.control.dryrun.judgment.changes", targetSystemId)
                .tag("actual", actual.name())
                .tag("candidate", candidate.name())
                .register(registry)
                .increment();
    }

    @Override
    public void recordDryRunTimeout(String targetSystemId) {
        dryRunCounter("flow.control.dryrun.timeouts", targetSystemId)
                .tag("result", "would_timeout")
                .register(registry)
                .increment();
    }

    @Override
    public void recordDryRunUnknownTimeout(String targetSystemId) {
        dryRunCounter("flow.control.dryrun.timeouts", targetSystemId)
                .tag("result", "unknown")
                .register(registry)
                .increment();
    }

    @Override
    public void recordDryRunStateTransition(String targetSystemId, HealthState from, HealthState to) {
        dryRunCounter("flow.control.dryrun.health.transitions", targetSystemId)
                .tag("from", from.name())
                .tag("to", to.name())
                .register(registry)
                .increment();
    }

    @Override
    public void recordLimitStoreSwitch(LimitStore to) {
        limitStoreSwitches(to).increment();
    }

    private Counter limitStoreSwitches(LimitStore to) {
        return Counter.builder("flow.control.limit.store.switches")
                .tag("to", to.name().toLowerCase())
                .register(registry);
    }

    @Override
    public void recordRedisLatency(Duration latency) {
        Timer.builder("flow.control.redis.latency")
                .publishPercentileHistogram()
                .minimumExpectedValue(REDIS_LATENCY_MIN)
                .maximumExpectedValue(REDIS_LATENCY_MAX)
                .register(registry)
                .record(latency);
    }

    @Override
    public void recordRedisError(String operation, boolean timeout) {
        Counter.builder("flow.control.redis.errors")
                .tag("operation", operation)
                .tag("reason", timeout ? "timeout" : "failure")
                .register(registry)
                .increment();
    }

    @Override
    public void recordSuppressed(String targetSystemId, SuppressedBy reason) {
        suppressed(targetSystemId, reason).increment();
    }

    private Counter suppressed(String targetSystemId, SuppressedBy reason) {
        return Counter.builder("flow.control.health.suppressed")
                .tag("target", targetSystemId)
                .tag("reason", reason.name().toLowerCase())
                .register(registry);
    }

    @Override
    public void recordModeTransition(String targetSystemId, HealthMode to) {
        modeTransitions(targetSystemId, to).increment();
    }

    private Counter modeTransitions(String targetSystemId, HealthMode to) {
        return Counter.builder("flow.control.health.mode.transitions")
                .tag("target", targetSystemId)
                .tag("to", to.name().toLowerCase())
                .register(registry);
    }

    @Override
    public void recordLowTrafficEvent(String targetSystemId, LowTrafficEvent event) {
        lowTrafficEvents(targetSystemId, event).increment();
    }

    private Counter lowTrafficEvents(String targetSystemId, LowTrafficEvent event) {
        return Counter.builder("flow.control.health.low.traffic.events")
                .tag("target", targetSystemId)
                .tag("event", event.name().toLowerCase())
                .register(registry);
    }

    @Override
    public void recordMaintenanceStarted(String targetSystemId, MaintenanceSource source) {
        maintenanceStarted(targetSystemId, source).increment();
    }

    private Counter maintenanceStarted(String targetSystemId, MaintenanceSource source) {
        return Counter.builder("flow.control.maintenance.started")
                .tag("target", targetSystemId)
                .tag("source", source.label())
                .register(registry);
    }

    @Override
    public void recordMaintenanceExcluded(String targetSystemId) {
        maintenanceExcluded(targetSystemId).increment();
    }

    private Counter maintenanceExcluded(String targetSystemId) {
        return Counter.builder("flow.control.maintenance.excluded")
                .tag("target", targetSystemId)
                .register(registry);
    }

    @Override
    public void recordExpired(String targetSystemId, ExpiryPoint where) {
        Counter.builder("flow.control.expired")
                .tag("target", targetSystemId)
                .tag("where", where.label())
                .register(registry)
                .increment();
    }

    @Override
    public void recordRequestAge(String targetSystemId, Duration age) {
        Timer.builder("flow.control.request.age")
                .tag("target", targetSystemId)
                .publishPercentileHistogram()
                .minimumExpectedValue(REQUEST_AGE_MIN)
                .maximumExpectedValue(REQUEST_AGE_MAX)
                .register(registry)
                .record(age);
    }

    private static Counter.Builder dryRunCounter(String name, String targetSystemId) {
        return Counter.builder(name).tag("target", targetSystemId);
    }

    private void recordBlocked(String targetSystemId, String reason) {
        Counter.builder("flow.control.outbound.blocked.requests")
                .tag("target", targetSystemId)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
