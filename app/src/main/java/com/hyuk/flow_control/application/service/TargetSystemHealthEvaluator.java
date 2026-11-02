package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.HealthMode;
import com.hyuk.flow_control.domain.health.HealthRules;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.HealthStatus;
import com.hyuk.flow_control.domain.health.HealthThresholds;
import com.hyuk.flow_control.domain.health.HealthTracker;
import com.hyuk.flow_control.domain.health.LowTrafficEvent;
import com.hyuk.flow_control.domain.health.LowTrafficRules;
import com.hyuk.flow_control.domain.health.OutcomeStatistics;
import com.hyuk.flow_control.domain.health.OutcomeWindow;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(0)
public class TargetSystemHealthEvaluator implements PolicyChangeListener {
    private static final Logger log = LoggerFactory.getLogger(TargetSystemHealthEvaluator.class);

    private final TargetSystemOutcomeRecorder recorder;
    private final BaselineEstimator baselines;
    private final MaintenanceMonitor maintenance;
    private final FlowControlMetrics metrics;
    private final HealthRules rules;
    private final int minimumCalls;
    private final LowTrafficRules lowTraffic;
    private final Map<String, Target> targets = new ConcurrentHashMap<>();

    public TargetSystemHealthEvaluator(
            TargetSystemOutcomeRecorder recorder,
            TargetSystemPolicyReader policyReader,
            BaselineEstimator baselines,
            MaintenanceMonitor maintenance,
            HealthProperties healthProperties,
            FlowControlMetrics metrics) {
        this.recorder = recorder;
        this.baselines = baselines;
        this.maintenance = maintenance;
        this.metrics = metrics;
        this.rules = healthProperties.rules();
        this.minimumCalls = healthProperties.minimumCalls();
        this.lowTraffic = healthProperties.lowTraffic().rules();

        policyReader.readAll().keySet().forEach(targetSystemId -> targets.put(targetSystemId, newTarget()));
    }

    public static HealthThresholds thresholdsOf(HealthProperties healthProperties) {
        return healthProperties.thresholds();
    }

    public static int requiredGoodCountOf(HealthProperties healthProperties) {
        return healthProperties.requiredGoodCount();
    }

    @Override
    public void onPolicyAdded(ManagedPolicy policy) {
        targets.putIfAbsent(policy.targetSystemId(), newTarget());
    }

    @Override
    public void onPolicyChanged(ManagedPolicy before, ManagedPolicy after) {
    }

    public HealthRules rules() {
        return rules;
    }

    public void evaluateAll() {
        targets.keySet().forEach(targetSystemId -> {
            try {
                evaluate(targetSystemId);
            } catch (RuntimeException e) {
                log.warn("대상 시스템 상태 판정 실패 : {}", targetSystemId, e);
            }
        });
    }

    public HealthSnapshot snapshotOf(String targetSystemId) {
        return targetOf(targetSystemId).snapshot;
    }

    public HealthStatus statusOf(String targetSystemId) {
        return targetOf(targetSystemId).tracker.status();
    }

    public synchronized void onMaintenanceEnded(String targetSystemId) {
        Target target = targetOf(targetSystemId);
        HealthStatus before = target.tracker.status();

        HealthMode modeBefore = target.tracker.restartRecovering();
        recordModeChange(targetSystemId, modeBefore, HealthMode.ADAPTIVE);
        HealthSnapshot last = target.snapshot;
        target.snapshot = new HealthSnapshot(HealthState.RECOVERING, last.sampleCount(), last.failureRate(), last.p95(),
                Instant.now(), HealthMode.ADAPTIVE, last.windowSpan(), 0, last.baselineP95());

        recordTransition(targetSystemId, before, target.tracker.status());
    }

    synchronized void evaluate(String targetSystemId) {
        Target target = targetOf(targetSystemId);
        Instant now = Instant.now();

        if (maintenance.isActive(targetSystemId)) {
            recorder.drainEvents(targetSystemId);
            recorder.drainNewSamples(targetSystemId);
            target.tracker.pause();
            return;
        }

        OutcomeWindow window = recorder.window(targetSystemId);
        OutcomeStatistics statistics = window.statistics();
        Duration baselineP95 = baselines.effective(targetSystemId).p95();
        Set<LowTrafficEvent> events = recorder.drainEvents(targetSystemId);
        int samples = recorder.drainNewSamples(targetSystemId);

        HealthTracker.Step step = target.tracker.judge(statistics, baselineP95, events, samples,
                () -> recorder.blockedWithinWindow(targetSystemId));

        recordModeChange(targetSystemId, step.modeBefore(), step.mode());
        step.suppressedBy().ifPresent(reason -> metrics.recordSuppressed(targetSystemId, reason));
        if (step.criticalByEvents()) {
            step.events().forEach(event -> metrics.recordLowTrafficEvent(targetSystemId, event));
            log.info("저트래픽 모드에서 사건이 나서 CRITICAL 로 내립니다 : {} {}", targetSystemId, step.events());
        }

        HealthStatus next = step.next();
        target.snapshot = new HealthSnapshot(
                next.state(),
                statistics.sampleCount(),
                statistics.failureRate(),
                statistics.p95(),
                now,
                step.mode(),
                window.span(),
                next.degradedStreak(),
                baselineP95,
                step.grade().orElse(null),
                step.samples());

        recordTransition(targetSystemId, step.before(), next);
    }

    private void recordModeChange(String targetSystemId, HealthMode from, HealthMode to) {
        if (from == to) {
            return;
        }

        metrics.recordModeTransition(targetSystemId, to);
        log.info("대상 시스템 판정 모드 변경 : {} {} -> {}", targetSystemId, from, to);
    }

    private void recordTransition(String targetSystemId, HealthStatus current, HealthStatus next) {
        if (next.state() != current.state()) {
            metrics.recordStateTransition(targetSystemId, current.state(), next.state());
            log.info("대상 시스템 상태 변경 : {} {} -> {}", targetSystemId, current.state(), next.state());
        }
    }

    private Target targetOf(String targetSystemId) {
        Target target = targets.get(targetSystemId);

        if (target == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return target;
    }

    private Target newTarget() {
        return new Target(new HealthTracker(rules, minimumCalls, lowTraffic, HealthStatus.initial()));
    }

    private static final class Target {
        private final HealthTracker tracker;
        private volatile HealthSnapshot snapshot = HealthSnapshot.initial();

        private Target(HealthTracker tracker) {
            this.tracker = tracker;
        }
    }
}
