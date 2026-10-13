package com.hyuk.flow_control.adapter.out.metrics;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.service.TargetSystemHealthEvaluator;
import com.hyuk.flow_control.application.service.TargetSystemLimitAdjuster;
import com.hyuk.flow_control.application.service.TargetSystemSemaphore;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

@Component
public class FlowControlGaugeRegistrar {
    private final MeterRegistry registry;
    private final TargetSystemHealthEvaluator evaluator;
    private final TargetSystemLimitAdjuster adjuster;
    private final TargetSystemSemaphore semaphore;
    private final double degradedLatencyMultiplier;

    public FlowControlGaugeRegistrar(
            MeterRegistry registry,
            TargetSystemPolicyReader policyReader,
            TargetSystemHealthEvaluator evaluator,
            TargetSystemLimitAdjuster adjuster,
            TargetSystemSemaphore semaphore,
            HealthProperties healthProperties) {
        this.registry = registry;
        this.evaluator = evaluator;
        this.adjuster = adjuster;
        this.semaphore = semaphore;
        this.degradedLatencyMultiplier = healthProperties.degradedLatencyMultiplier();

        policyReader.readAll().forEach(this::registerGauges);
    }

    private void registerGauges(String targetSystemId, TargetSystemPolicy policy) {
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
                () -> toSeconds(policy.baselineRtt()));
        register("flow.control.health.latency.threshold", targetSystemId, "seconds",
                () -> toSeconds(policy.baselineRtt()) * degradedLatencyMultiplier);

        register("flow.control.limit.rate", targetSystemId, null,
                () -> adjuster.rateOf(targetSystemId));
        register("flow.control.limit.rate.max", targetSystemId, null,
                policy::effectiveRate);
        register("flow.control.limit.concurrency", targetSystemId, null,
                () -> semaphore.limitOf(targetSystemId));
        register("flow.control.inflight", targetSystemId, null,
                () -> semaphore.inFlightOf(targetSystemId));
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
