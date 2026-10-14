package com.hyuk.flow_control.adapter.out.metrics;

import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.service.DryRunMonitor;
import com.hyuk.flow_control.application.service.PolicyRegistry;
import com.hyuk.flow_control.application.service.TargetSystemHealthEvaluator;
import com.hyuk.flow_control.application.service.TargetSystemLimitAdjuster;
import com.hyuk.flow_control.application.service.TargetSystemSemaphore;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

@Component
@Order(0)
public class FlowControlGaugeRegistrar implements PolicyChangeListener {
    private final MeterRegistry registry;
    private final PolicyRegistry policies;
    private final TargetSystemHealthEvaluator evaluator;
    private final TargetSystemLimitAdjuster adjuster;
    private final TargetSystemSemaphore semaphore;
    private final DryRunMonitor dryRuns;
    private final double degradedLatencyMultiplier;

    public FlowControlGaugeRegistrar(
            MeterRegistry registry,
            PolicyRegistry policies,
            TargetSystemHealthEvaluator evaluator,
            TargetSystemLimitAdjuster adjuster,
            TargetSystemSemaphore semaphore,
            DryRunMonitor dryRuns,
            HealthProperties healthProperties) {
        this.registry = registry;
        this.policies = policies;
        this.evaluator = evaluator;
        this.adjuster = adjuster;
        this.semaphore = semaphore;
        this.dryRuns = dryRuns;
        this.degradedLatencyMultiplier = healthProperties.degradedLatencyMultiplier();

        policies.readAll().keySet().forEach(this::registerGauges);
    }

    @Override
    public void onPolicyAdded(ManagedPolicy policy) {
        registerGauges(policy.targetSystemId());
    }

    @Override
    public void onPolicyChanged(ManagedPolicy before, ManagedPolicy after) {
    }

    private void registerGauges(String targetSystemId) {
        for (HealthState state : HealthState.values()) {
            Gauge.builder("flow.control.health.state",
                            () -> evaluator.snapshotOf(targetSystemId).state() == state ? 1 : 0)
                    .tag("target", targetSystemId)
                    .tag("state", state.name())
                    .register(registry);
        }

        register("flow.control.health.failure.rate", targetSystemId, null,
                () -> evaluator.snapshotOf(targetSystemId).failureRate());
        register("flow.control.health.p95", targetSystemId, "seconds",
                () -> secondsOrNaN(evaluator.snapshotOf(targetSystemId).p95()));
        register("flow.control.health.samples", targetSystemId, null,
                () -> evaluator.snapshotOf(targetSystemId).sampleCount());
        register("flow.control.health.baseline.rtt", targetSystemId, "seconds",
                () -> toSeconds(policies.read(targetSystemId).baselineRtt()));
        register("flow.control.health.latency.threshold", targetSystemId, "seconds",
                () -> toSeconds(policies.read(targetSystemId).baselineRtt()) * degradedLatencyMultiplier);

        register("flow.control.limit.rate", targetSystemId, null,
                () -> adjuster.rateOf(targetSystemId));
        register("flow.control.limit.rate.max", targetSystemId, null,
                () -> policies.read(targetSystemId).effectiveRate());
        register("flow.control.limit.concurrency", targetSystemId, null,
                () -> semaphore.limitOf(targetSystemId));
        register("flow.control.inflight", targetSystemId, null,
                () -> semaphore.inFlightOf(targetSystemId));
        register("flow.control.policy.version", targetSystemId, null,
                () -> policies.policyOf(targetSystemId).version());

        registerDryRunGauges(targetSystemId);
    }

    private void registerDryRunGauges(String targetSystemId) {
        register("flow.control.dryrun.active", targetSystemId, null,
                () -> dryRuns.isRunning(targetSystemId) ? 1 : 0);

        for (HealthState state : HealthState.values()) {
            Gauge.builder("flow.control.dryrun.health.state",
                            () -> dryRuns.stateOf(targetSystemId)
                                    .map(current -> current == state ? 1.0 : 0.0)
                                    .orElse(Double.NaN))
                    .tag("target", targetSystemId)
                    .tag("state", state.name())
                    .register(registry);
        }

        register("flow.control.dryrun.limit.rate", targetSystemId, null,
                () -> dryRuns.rateOf(targetSystemId).orElse(Double.NaN));
        register("flow.control.dryrun.limit.concurrency", targetSystemId, null,
                () -> dryRuns.concurrencyLimitOf(targetSystemId).map(Integer::doubleValue).orElse(Double.NaN));
    }

    private void register(String name, String targetSystemId, String baseUnit, Supplier<Number> value) {
        Gauge.builder(name, value)
                .tag("target", targetSystemId)
                .baseUnit(baseUnit)
                .register(registry);
    }

    private static double secondsOrNaN(Duration duration) {
        return duration == null ? Double.NaN : toSeconds(duration);
    }

    private static double toSeconds(Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }
}
