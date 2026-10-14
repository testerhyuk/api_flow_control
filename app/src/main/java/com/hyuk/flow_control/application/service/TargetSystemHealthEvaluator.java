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
import com.hyuk.flow_control.domain.health.LowTrafficEvent;
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
    private final int lowTrafficEnterAfter;
    private final Duration quietPeriod;
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
        this.lowTrafficEnterAfter = healthProperties.lowTraffic().enterAfter();
        this.quietPeriod = healthProperties.recoveryDuration();

        policyReader.readAll().keySet().forEach(targetSystemId -> targets.put(targetSystemId, new Target()));
    }

    public static HealthThresholds thresholdsOf(HealthProperties healthProperties) {
        return healthProperties.thresholds();
    }

    public static int requiredGoodCountOf(HealthProperties healthProperties) {
        return healthProperties.requiredGoodCount();
    }

    @Override
    public void onPolicyAdded(ManagedPolicy policy) {
        targets.putIfAbsent(policy.targetSystemId(), new Target());
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
        return targetOf(targetSystemId).status;
    }

    public synchronized void onMaintenanceEnded(String targetSystemId) {
        Target target = targetOf(targetSystemId);
        HealthStatus before = target.status;

        target.status = HealthStatus.of(HealthState.RECOVERING);
        target.lowTrafficTicks = 0;
        changeMode(targetSystemId, target, HealthMode.ADAPTIVE);
        HealthSnapshot last = target.snapshot;
        target.snapshot = new HealthSnapshot(HealthState.RECOVERING, last.sampleCount(), last.failureRate(), last.p95(),
                Instant.now(), HealthMode.ADAPTIVE, last.windowSpan(), 0, last.baselineRtt());

        recordTransition(targetSystemId, before, target.status);
    }

    synchronized void evaluate(String targetSystemId) {
        Target target = targetOf(targetSystemId);
        Instant now = Instant.now();

        if (maintenance.isActive(targetSystemId)) {
            recorder.drainEvents(targetSystemId);
            target.lowTrafficTicks = 0;
            return;
        }

        OutcomeWindow window = recorder.window(targetSystemId);
        OutcomeStatistics statistics = window.statistics();
        Duration baselineRtt = baselines.effective(targetSystemId);
        Set<LowTrafficEvent> events = recorder.drainEvents(targetSystemId);
        HealthStatus current = target.status;
        boolean enough = statistics.sampleCount() >= minimumCalls;

        if (target.mode == HealthMode.ADAPTIVE && !enough) {
            target.lowTrafficTicks = recorder.blockedWithinWindow(targetSystemId) ? 0 : target.lowTrafficTicks + 1;

            if (target.lowTrafficTicks >= lowTrafficEnterAfter) {
                target.lowTrafficTicks = 0;
                changeMode(targetSystemId, target, HealthMode.LOW_TRAFFIC);
                if (current.state() == HealthState.CRITICAL) {
                    target.lastEventAt = now;
                }
            }
        } else if (target.mode == HealthMode.ADAPTIVE) {
            target.lowTrafficTicks = 0;
        } else if (enough) {
            changeMode(targetSystemId, target, HealthMode.ADAPTIVE);
        }

        HealthStatus next = target.mode == HealthMode.LOW_TRAFFIC
                ? inLowTraffic(targetSystemId, target, current, events, now)
                : judge(targetSystemId, current, statistics, baselineRtt);

        target.status = next;
        target.snapshot = new HealthSnapshot(
                next.state(),
                statistics.sampleCount(),
                statistics.failureRate(),
                statistics.p95(),
                now,
                target.mode,
                window.span(),
                next.degradedStreak(),
                baselineRtt);

        recordTransition(targetSystemId, current, next);
    }

    private HealthStatus judge(String targetSystemId, HealthStatus current, OutcomeStatistics statistics,
                               Duration baselineRtt) {
        HealthRules.Judgment judgment = rules.judge(current, statistics, baselineRtt);
        judgment.suppressedBy().ifPresent(reason -> metrics.recordSuppressed(targetSystemId, reason));
        return judgment.next();
    }

    private HealthStatus inLowTraffic(String targetSystemId, Target target, HealthStatus current,
                                      Set<LowTrafficEvent> events, Instant now) {
        if (!events.isEmpty()) {
            events.forEach(event -> metrics.recordLowTrafficEvent(targetSystemId, event));
            target.lastEventAt = now;
            log.info("저트래픽 모드에서 사건이 나서 CRITICAL 로 내립니다 : {} {}", targetSystemId, events);
            return HealthStatus.of(HealthState.CRITICAL);
        }

        if (current.state() == HealthState.CRITICAL
                && target.lastEventAt != null
                && !now.isBefore(target.lastEventAt.plus(quietPeriod))) {
            target.lastEventAt = null;
            return HealthStatus.initial();
        }

        return current;
    }

    private void changeMode(String targetSystemId, Target target, HealthMode to) {
        if (target.mode == to) {
            return;
        }

        HealthMode from = target.mode;
        target.mode = to;
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

    private static final class Target {
        private volatile HealthStatus status = HealthStatus.initial();
        private volatile HealthSnapshot snapshot = HealthSnapshot.initial();
        private volatile HealthMode mode = HealthMode.ADAPTIVE;
        private int lowTrafficTicks;
        private Instant lastEventAt;
    }
}
