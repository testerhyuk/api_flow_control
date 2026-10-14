package com.hyuk.flow_control.adapter.out.metrics;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.retry.DeadLetterReason;
import com.hyuk.flow_control.domain.retry.RetryStage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MicrometerFlowControlMetrics implements FlowControlMetrics {
    private static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(10),
            Duration.ofMillis(25),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(200),
            Duration.ofMillis(300),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            Duration.ofSeconds(5)
    };

    private final MeterRegistry registry;

    public MicrometerFlowControlMetrics(MeterRegistry registry) {
        this.registry = registry;
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
                .serviceLevelObjectives(LATENCY_BUCKETS)
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
        Counter.builder("flow.control.health.transitions")
                .tag("target", targetSystemId)
                .tag("from", from.name())
                .tag("to", to.name())
                .register(registry)
                .increment();
    }

    @Override
    public void recordIntakeWait(String targetSystemId, Duration waited) {
        Timer.builder("flow.control.intake.wait")
                .tag("target", targetSystemId)
                .serviceLevelObjectives(LATENCY_BUCKETS)
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
        Counter.builder("flow.control.policy.refresh.failures")
                .register(registry)
                .increment();
    }

    @Override
    public void recordInvalidPolicy(String targetSystemId) {
        Counter.builder("flow.control.policy.invalid")
                .tag("target", targetSystemId)
                .register(registry)
                .increment();
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

    private static Counter.Builder dryRunCounter(String name, String targetSystemId) {
        return Counter.builder(name).tag("target", targetSystemId);
    }

    private void recordBlocked(String targetSystemId, String reason) {
        Counter.builder("flow.control.outbound.blocked.attempts")
                .tag("target", targetSystemId)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
