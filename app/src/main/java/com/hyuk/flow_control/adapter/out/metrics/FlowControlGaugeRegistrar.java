package com.hyuk.flow_control.adapter.out.metrics;

import com.hyuk.flow_control.application.port.in.FlowControlStatusQuery;
import com.hyuk.flow_control.application.port.in.PolicyQuery;
import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.config.ControlProperties;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.BaselineBound;
import com.hyuk.flow_control.domain.health.BaselineKind;
import com.hyuk.flow_control.domain.health.HealthMode;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.HealthThresholds;
import com.hyuk.flow_control.domain.limit.ControlMode;
import com.hyuk.flow_control.domain.maintenance.MaintenanceSource;
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
    private final PolicyQuery policies;
    private final FlowControlStatusQuery status;
    private final HealthThresholds thresholds;

    public FlowControlGaugeRegistrar(
            MeterRegistry registry,
            PolicyQuery policies,
            FlowControlStatusQuery status,
            HealthProperties healthProperties,
            ControlProperties controlProperties) {
        this.registry = registry;
        this.policies = policies;
        this.status = status;
        this.thresholds = status.activeThresholds();

        registerGlobalGauges(controlProperties.controlMode(), healthProperties);
        policies.readAll().keySet().forEach(this::registerGauges);
    }

    @Override
    public void onPolicyAdded(ManagedPolicy policy) {
        registerGauges(policy.targetSystemId());
    }

    @Override
    public void onPolicyChanged(ManagedPolicy before, ManagedPolicy after) {
    }

    private void registerGlobalGauges(ControlMode controlMode, HealthProperties healthProperties) {
        for (ControlMode mode : ControlMode.values()) {
            Gauge.builder("flow.control.control.mode", () -> mode == controlMode ? 1 : 0)
                    .tag("mode", mode.label())
                    .register(registry);
        }

        Gauge.builder("flow.control.health.anti.oscillation", () -> healthProperties.antiOscillation() ? 1 : 0)
                .register(registry);
        Gauge.builder("flow.control.health.baseline.auto", () -> healthProperties.baselineAuto() ? 1 : 0)
                .register(registry);
    }

    private void registerGauges(String targetSystemId) {
        for (HealthState state : HealthState.values()) {
            Gauge.builder("flow.control.health.state",
                            () -> status.healthOf(targetSystemId).state() == state ? 1 : 0)
                    .tag("target", targetSystemId)
                    .tag("state", state.name())
                    .register(registry);
        }

        register("flow.control.health.failure.rate", targetSystemId, null,
                () -> status.healthOf(targetSystemId).failureRate());
        register("flow.control.health.p95", targetSystemId, "seconds",
                () -> secondsOrNaN(status.healthOf(targetSystemId).p95()));
        register("flow.control.health.samples", targetSystemId, null,
                () -> status.healthOf(targetSystemId).sampleCount());
        registerFailureThreshold(targetSystemId, "degraded", "entry", thresholds.degradedFailureRate());
        registerFailureThreshold(targetSystemId, "degraded", "recovery", thresholds.degradedRecoveryFailureRate());
        registerFailureThreshold(targetSystemId, "critical", "entry", thresholds.criticalFailureRate());
        registerFailureThreshold(targetSystemId, "critical", "recovery", thresholds.criticalRecoveryFailureRate());
        register("flow.control.health.latency.threshold", targetSystemId, "seconds",
                () -> toSeconds(thresholds.latencyThreshold(status.baselinesOf(targetSystemId).p95())));
        register("flow.control.health.latency.recovery.threshold", targetSystemId, "seconds",
                () -> toSeconds(thresholds.recoveryLatencyThreshold(status.baselinesOf(targetSystemId).p95())));
        register("flow.control.health.degraded.streak", targetSystemId, null,
                () -> status.healthOf(targetSystemId).degradedStreak());
        register("flow.control.health.window.span", targetSystemId, "seconds",
                () -> secondsOrNaN(status.healthOf(targetSystemId).windowSpan()));

        for (HealthMode mode : HealthMode.values()) {
            Gauge.builder("flow.control.health.mode",
                            () -> status.healthOf(targetSystemId).mode() == mode ? 1 : 0)
                    .tag("target", targetSystemId)
                    .tag("mode", mode.name().toLowerCase())
                    .register(registry);
        }

        register("flow.control.health.baseline.samples", targetSystemId, null,
                () -> status.baselineSampleCount(targetSystemId));

        for (BaselineKind kind : BaselineKind.values()) {
            registerBaseline(targetSystemId, kind);
        }

        for (MaintenanceSource source : MaintenanceSource.values()) {
            Gauge.builder("flow.control.maintenance.active",
                            () -> status.maintenanceActive(targetSystemId, source) ? 1 : 0)
                    .tag("target", targetSystemId)
                    .tag("source", source.label())
                    .register(registry);
        }

        register("flow.control.limit.rate", targetSystemId, null,
                () -> status.appliedRate(targetSystemId));
        register("flow.control.limit.rate.proposed", targetSystemId, null,
                () -> status.proposedRate(targetSystemId));
        register("flow.control.limit.servers", targetSystemId, null,
                () -> status.servers(targetSystemId));
        register("flow.control.limit.rate.max", targetSystemId, null,
                () -> policies.read(targetSystemId).effectiveRate());
        register("flow.control.limit.concurrency", targetSystemId, null,
                () -> status.concurrencyLimit(targetSystemId));
        register("flow.control.inflight", targetSystemId, null,
                () -> status.inFlight(targetSystemId));
        register("flow.control.policy.version", targetSystemId, null,
                () -> policies.policyOf(targetSystemId).version());

        registerDryRunGauges(targetSystemId);
    }

    private void registerDryRunGauges(String targetSystemId) {
        register("flow.control.dryrun.active", targetSystemId, null,
                () -> status.dryRunActive(targetSystemId) ? 1 : 0);

        for (HealthState state : HealthState.values()) {
            Gauge.builder("flow.control.dryrun.health.state",
                            () -> status.dryRunState(targetSystemId)
                                    .map(current -> current == state ? 1.0 : 0.0)
                                    .orElse(Double.NaN))
                    .tag("target", targetSystemId)
                    .tag("state", state.name())
                    .register(registry);
        }

        register("flow.control.dryrun.limit.rate", targetSystemId, null,
                () -> status.dryRunRate(targetSystemId).orElse(Double.NaN));
        register("flow.control.dryrun.limit.concurrency", targetSystemId, null,
                () -> status.dryRunConcurrencyLimit(targetSystemId).map(Integer::doubleValue).orElse(Double.NaN));
        register("flow.control.dryrun.overlap", targetSystemId, "seconds",
                () -> status.dryRunOverlap(targetSystemId).map(FlowControlGaugeRegistrar::toSeconds).orElse(Double.NaN));
        register("flow.control.dryrun.judged.calls", targetSystemId, null,
                () -> status.dryRunJudgedCalls(targetSystemId).map(Long::doubleValue).orElse(Double.NaN));
        register("flow.control.dryrun.required.calls", targetSystemId, null,
                () -> status.dryRunActive(targetSystemId) ? status.dryRunRequiredCalls() : Double.NaN);
    }

    private void registerFailureThreshold(String targetSystemId, String grade, String line, double value) {
        Gauge.builder("flow.control.health.failure.threshold", () -> value)
                .tag("target", targetSystemId)
                .tag("grade", grade)
                .tag("line", line)
                .register(registry);
    }

    private void registerBaseline(String targetSystemId, BaselineKind kind) {
        Gauge.builder("flow.control.health.baseline.rtt",
                        () -> toSeconds(kind == BaselineKind.MEAN
                                ? status.baselinesOf(targetSystemId).mean()
                                : status.baselinesOf(targetSystemId).p95()))
                .tag("target", targetSystemId)
                .tag("kind", kind.label())
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("flow.control.health.baseline.policy",
                        () -> toSeconds(kind.policyValueOf(policies.read(targetSystemId))))
                .tag("target", targetSystemId)
                .tag("kind", kind.label())
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("flow.control.health.baseline.learned",
                        () -> status.learnedBaseline(targetSystemId, kind)
                                .map(FlowControlGaugeRegistrar::toSeconds)
                                .orElse(Double.NaN))
                .tag("target", targetSystemId)
                .tag("kind", kind.label())
                .baseUnit("seconds")
                .register(registry);

        for (BaselineBound bound : BaselineBound.values()) {
            Gauge.builder("flow.control.health.baseline.clamped",
                            () -> status.clampedBaseline(targetSystemId, kind).filter(bound::equals).isPresent() ? 1 : 0)
                    .tag("target", targetSystemId)
                    .tag("kind", kind.label())
                    .tag("bound", bound.label())
                    .register(registry);
        }
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
