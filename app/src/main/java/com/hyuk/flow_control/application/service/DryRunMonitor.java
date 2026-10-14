package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.DryRunRepository;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.ResponseJudge;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.config.LimitAdjustmentProperties;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunReport;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.HealthThresholds;
import com.hyuk.flow_control.domain.limit.RateAdjustmentRule;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.request.OutboundResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DryRunMonitor {
    private static final Logger log = LoggerFactory.getLogger(DryRunMonitor.class);

    private final DryRunRepository repository;
    private final PolicyRegistry policies;
    private final TargetSystemHealthEvaluator evaluator;
    private final TargetSystemLimitAdjuster adjuster;
    private final ResponseJudge judge;
    private final FlowControlMetrics metrics;
    private final HealthThresholds thresholds;
    private final RateAdjustmentRule rateRule;
    private final int requiredGoodCount;
    private final Duration window;
    private final Map<String, ShadowEvaluation> running = new ConcurrentHashMap<>();

    public DryRunMonitor(
            DryRunRepository repository,
            PolicyRegistry policies,
            TargetSystemHealthEvaluator evaluator,
            TargetSystemLimitAdjuster adjuster,
            ResponseJudge judge,
            FlowControlMetrics metrics,
            HealthProperties healthProperties,
            LimitAdjustmentProperties adjustmentProperties) {
        this.repository = repository;
        this.policies = policies;
        this.evaluator = evaluator;
        this.adjuster = adjuster;
        this.judge = judge;
        this.metrics = metrics;
        this.thresholds = TargetSystemHealthEvaluator.thresholdsOf(healthProperties);
        this.rateRule = TargetSystemLimitAdjuster.ruleOf(adjustmentProperties, healthProperties);
        this.requiredGoodCount = TargetSystemHealthEvaluator.requiredGoodCountOf(healthProperties);
        this.window = healthProperties.window();
    }

    public synchronized void refresh() {
        Instant now = Instant.now();
        List<DryRun> stored;

        try {
            repository.deleteStale(now);
            stored = repository.loadAll();
        } catch (RuntimeException e) {
            log.warn("dry-run 목록을 다시 읽지 못했습니다. 진행 중인 dry-run 은 끝 시각까지 계속합니다", e);
            stopExpired(now);
            return;
        }

        Map<String, DryRun> byTarget = new HashMap<>();
        stored.forEach(dryRun -> byTarget.put(dryRun.targetSystemId(), dryRun));

        running.forEach((targetSystemId, evaluation) -> {
            DryRun latest = byTarget.get(targetSystemId);

            if (latest == null || !latest.equals(evaluation.dryRun())) {
                stop(targetSystemId, "취소되었거나 끝났습니다");
            } else if (isStale(evaluation.dryRun(), now)) {
                stop(targetSystemId, "끝 시각이 지났거나 정책 버전이 바뀌었습니다");
            }
        });

        byTarget.values().forEach(dryRun -> {
            if (!running.containsKey(dryRun.targetSystemId()) && !isStale(dryRun, now)) {
                start(dryRun, now);
            }
        });
    }

    public void evaluateAll() {
        Instant now = Instant.now();
        long nowNanos = System.nanoTime();

        running.forEach((targetSystemId, evaluation) -> {
            if (isStale(evaluation.dryRun(), now)) {
                stop(targetSystemId, "끝 시각이 지났거나 정책 버전이 바뀌었습니다");
                return;
            }

            try {
                evaluation.evaluate(now, nowNanos, adjuster.serversOf(targetSystemId));
            } catch (RuntimeException e) {
                log.warn("dry-run 상태 판정 실패 : {}", targetSystemId, e);
            }
        });
    }

    public Observation beforeCall(String targetSystemId) {
        ShadowEvaluation evaluation = running.get(targetSystemId);

        if (evaluation == null) {
            return Observation.NONE;
        }

        evaluation.admit(System.nanoTime());
        return new Observation(evaluation);
    }

    public void recordWaited(String targetSystemId) {
        ShadowEvaluation evaluation = running.get(targetSystemId);

        if (evaluation != null) {
            evaluation.recordWaited();
        }
    }

    public Optional<DryRunReport> reportOf(DryRun dryRun) {
        ShadowEvaluation evaluation = running.get(dryRun.targetSystemId());

        if (evaluation == null || !evaluation.dryRun().equals(dryRun)) {
            return Optional.empty();
        }

        String targetSystemId = dryRun.targetSystemId();
        return Optional.of(evaluation.report(
                Instant.now(),
                evaluator.snapshotOf(targetSystemId).state(),
                adjuster.appliedRateOf(targetSystemId)));
    }

    public boolean isRunning(String targetSystemId) {
        return running.containsKey(targetSystemId);
    }

    public Optional<HealthState> stateOf(String targetSystemId) {
        return Optional.ofNullable(running.get(targetSystemId)).map(ShadowEvaluation::state);
    }

    public Optional<Double> rateOf(String targetSystemId) {
        return Optional.ofNullable(running.get(targetSystemId)).map(ShadowEvaluation::rate);
    }

    public Optional<Integer> concurrencyLimitOf(String targetSystemId) {
        return Optional.ofNullable(running.get(targetSystemId)).map(ShadowEvaluation::concurrencyLimit);
    }

    public Optional<Duration> overlapOf(String targetSystemId) {
        return Optional.ofNullable(running.get(targetSystemId)).map(ShadowEvaluation::overlap);
    }

    private void start(DryRun dryRun, Instant now) {
        String targetSystemId = dryRun.targetSystemId();
        ManagedPolicy base;

        try {
            base = policies.policyOf(targetSystemId);
        } catch (IllegalArgumentException e) {
            return;
        }

        if (base.version() != dryRun.baseVersion()) {
            return;
        }

        running.put(targetSystemId, new ShadowEvaluation(
                dryRun,
                base,
                evaluator.statusOf(targetSystemId),
                adjuster.appliedRateOf(targetSystemId),
                judge,
                thresholds,
                rateRule,
                requiredGoodCount,
                window,
                metrics,
                now,
                System.nanoTime()));

        log.info("dry-run 을 시작했습니다 : {} 버전 {} 기준, {} 까지", targetSystemId, dryRun.baseVersion(), dryRun.expiresAt());
    }

    private void stop(String targetSystemId, String reason) {
        if (running.remove(targetSystemId) != null) {
            log.info("dry-run 을 멈췄습니다 : {} ({})", targetSystemId, reason);
        }
    }

    private void stopExpired(Instant now) {
        running.forEach((targetSystemId, evaluation) -> {
            if (isStale(evaluation.dryRun(), now)) {
                stop(targetSystemId, "끝 시각이 지났거나 정책 버전이 바뀌었습니다");
            }
        });
    }

    private boolean isStale(DryRun dryRun, Instant now) {
        if (dryRun.isExpiredAt(now)) {
            return true;
        }

        try {
            return policies.policyOf(dryRun.targetSystemId()).version() != dryRun.baseVersion();
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    public static final class Observation {
        static final Observation NONE = new Observation(null);

        private final ShadowEvaluation evaluation;

        private Observation(ShadowEvaluation evaluation) {
            this.evaluation = evaluation;
        }

        public void finish(OutboundResult result) {
            if (evaluation == null) {
                return;
            }

            evaluation.release();
            if (result != null) {
                evaluation.complete(result, Instant.now());
            }
        }
    }
}
