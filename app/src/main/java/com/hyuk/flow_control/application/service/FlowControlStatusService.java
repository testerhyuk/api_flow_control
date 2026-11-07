package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.FlowControlStatusQuery;
import com.hyuk.flow_control.domain.health.BaselineBound;
import com.hyuk.flow_control.domain.health.BaselineKind;
import com.hyuk.flow_control.domain.health.Baselines;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.HealthThresholds;
import com.hyuk.flow_control.domain.maintenance.MaintenanceSource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class FlowControlStatusService implements FlowControlStatusQuery {
    private final TargetSystemHealthEvaluator evaluator;
    private final TargetSystemLimitAdjuster adjuster;
    private final TargetSystemSemaphore semaphore;
    private final DryRunMonitor dryRuns;
    private final BaselineEstimator baselines;
    private final MaintenanceMonitor maintenance;

    public FlowControlStatusService(
            TargetSystemHealthEvaluator evaluator,
            TargetSystemLimitAdjuster adjuster,
            TargetSystemSemaphore semaphore,
            DryRunMonitor dryRuns,
            BaselineEstimator baselines,
            MaintenanceMonitor maintenance) {
        this.evaluator = evaluator;
        this.adjuster = adjuster;
        this.semaphore = semaphore;
        this.dryRuns = dryRuns;
        this.baselines = baselines;
        this.maintenance = maintenance;
    }

    @Override
    public HealthThresholds activeThresholds() {
        return evaluator.rules().activeThresholds();
    }

    @Override
    public HealthSnapshot healthOf(String targetSystemId) {
        return evaluator.snapshotOf(targetSystemId);
    }

    @Override
    public Baselines baselinesOf(String targetSystemId) {
        return baselines.effective(targetSystemId);
    }

    @Override
    public int baselineSampleCount(String targetSystemId) {
        return baselines.sampleCount(targetSystemId);
    }

    @Override
    public Optional<Duration> learnedBaseline(String targetSystemId, BaselineKind kind) {
        return baselines.learned(targetSystemId, kind);
    }

    @Override
    public Optional<BaselineBound> clampedBaseline(String targetSystemId, BaselineKind kind) {
        return baselines.clampedBound(targetSystemId, kind);
    }

    @Override
    public boolean maintenanceActive(String targetSystemId, MaintenanceSource source) {
        return maintenance.isActive(targetSystemId, source);
    }

    @Override
    public double appliedRate(String targetSystemId) {
        return adjuster.appliedRateOf(targetSystemId);
    }

    @Override
    public double proposedRate(String targetSystemId) {
        return adjuster.rateOf(targetSystemId);
    }

    @Override
    public int servers(String targetSystemId) {
        return adjuster.serversOf(targetSystemId);
    }

    @Override
    public int concurrencyLimit(String targetSystemId) {
        return semaphore.limitOf(targetSystemId);
    }

    @Override
    public int inFlight(String targetSystemId) {
        return semaphore.inFlightOf(targetSystemId);
    }

    @Override
    public boolean dryRunActive(String targetSystemId) {
        return dryRuns.isRunning(targetSystemId);
    }

    @Override
    public Optional<HealthState> dryRunState(String targetSystemId) {
        return dryRuns.stateOf(targetSystemId);
    }

    @Override
    public Optional<Double> dryRunRate(String targetSystemId) {
        return dryRuns.rateOf(targetSystemId);
    }

    @Override
    public Optional<Integer> dryRunConcurrencyLimit(String targetSystemId) {
        return dryRuns.concurrencyLimitOf(targetSystemId);
    }

    @Override
    public Optional<Duration> dryRunOverlap(String targetSystemId) {
        return dryRuns.overlapOf(targetSystemId);
    }

    @Override
    public Optional<Long> dryRunJudgedCalls(String targetSystemId) {
        return dryRuns.judgedCallsOf(targetSystemId);
    }

    @Override
    public int dryRunRequiredCalls() {
        return dryRuns.requiredCalls();
    }
}
