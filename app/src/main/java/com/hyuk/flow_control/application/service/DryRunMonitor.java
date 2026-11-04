package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.DryRunRepository;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.ResponseJudge;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.config.LimitAdjustmentProperties;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunEnd;
import com.hyuk.flow_control.domain.dryrun.DryRunResult;
import com.hyuk.flow_control.domain.dryrun.DryRunReport;
import com.hyuk.flow_control.domain.health.LowTrafficRules;
import com.hyuk.flow_control.domain.health.HealthRules;
import com.hyuk.flow_control.domain.health.HealthState;
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
    private final BaselineEstimator baselines;
    private final MaintenanceMonitor maintenance;
    private final ResponseJudge judge;
    private final FlowControlMetrics metrics;
    private final HealthRules rules;
    private final LowTrafficRules lowTraffic;
    private final RateAdjustmentRule rateRule;
    private final Duration window;
    private final int maxSamples;
    private final Map<String, ShadowEvaluation> running = new ConcurrentHashMap<>();
    private final Map<String, FinishedDryRun> finished = new ConcurrentHashMap<>();

    public DryRunMonitor(
            DryRunRepository repository,
            PolicyRegistry policies,
            TargetSystemHealthEvaluator evaluator,
            TargetSystemLimitAdjuster adjuster,
            BaselineEstimator baselines,
            MaintenanceMonitor maintenance,
            ResponseJudge judge,
            FlowControlMetrics metrics,
            HealthProperties healthProperties,
            LimitAdjustmentProperties adjustmentProperties) {
        this.repository = repository;
        this.policies = policies;
        this.evaluator = evaluator;
        this.adjuster = adjuster;
        this.baselines = baselines;
        this.maintenance = maintenance;
        this.judge = judge;
        this.metrics = metrics;
        this.rules = healthProperties.rules();
        this.lowTraffic = healthProperties.lowTraffic().rules();
        this.rateRule = TargetSystemLimitAdjuster.ruleOf(adjustmentProperties, healthProperties);
        this.window = healthProperties.window();
        this.maxSamples = healthProperties.minimumCalls();
    }

    public synchronized void refresh() {
        Instant now = Instant.now();
        List<DryRun> stored;

        try {
            repository.deleteStale(now).forEach(dryRun -> recordEnd(dryRun,
                    dryRun.isExpiredAt(now) ? DryRunEnd.EXPIRED : DryRunEnd.POLICY_CHANGED, null, null,
                    reportOf(dryRun).orElse(null)));
            stored = repository.loadAll();
        } catch (RuntimeException e) {
            log.warn("dry-run 목록을 다시 읽지 못했습니다. 진행 중인 dry-run 은 끝 시각까지 계속합니다", e);
            stopExpired(now);
            return;
        }

        finished.values().removeIf(ended -> !stored.contains(ended.dryRun()));

        Map<String, DryRun> byTarget = new HashMap<>();
        stored.forEach(dryRun -> byTarget.put(dryRun.targetSystemId(), dryRun));

        running.forEach((targetSystemId, evaluation) -> {
            DryRun latest = byTarget.get(targetSystemId);

            if (latest == null || !latest.equals(evaluation.dryRun())) {
                stop(targetSystemId, "취소되었거나 끝났습니다");
            } else if (isStale(evaluation.dryRun(), now)) {
                finish(targetSystemId, evaluation);
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
                finish(targetSystemId, evaluation);
                return;
            }

            try {
                evaluation.evaluate(now, nowNanos, adjuster.serversOf(targetSystemId),
                        candidateBaseline(evaluation.dryRun()),
                        maintenance.isActive(targetSystemId));
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

        boolean admitted = evaluation.admit(System.nanoTime());
        return new Observation(evaluation, admitted);
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
            return Optional.ofNullable(finished.get(dryRun.targetSystemId()))
                    .filter(ended -> ended.dryRun().equals(dryRun))
                    .map(FinishedDryRun::report);
        }

        return Optional.of(reportOf(evaluation));
    }

    private DryRunReport reportOf(ShadowEvaluation evaluation) {
        String targetSystemId = evaluation.dryRun().targetSystemId();
        return evaluation.report(
                Instant.now(),
                evaluator.snapshotOf(targetSystemId).state(),
                adjuster.appliedRateOf(targetSystemId));
    }

    public void recordEnd(DryRun dryRun, DryRunEnd end, String endedBy, Long appliedVersion, DryRunReport report) {
        DryRunResult result = new DryRunResult(dryRun, end, Instant.now(), endedBy, appliedVersion, report);

        try {
            repository.saveResult(result);
        } catch (RuntimeException e) {
            log.warn("dry-run 결과를 저장하지 못했습니다 : {} {}", dryRun.targetSystemId(), end, e);
        }
    }

    public int requiredCalls() {
        return maxSamples;
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
                rules,
                lowTraffic,
                rateRule,
                window,
                maxSamples,
                candidateBaseline(dryRun),
                metrics,
                now,
                System.nanoTime()));

        log.info("dry-run 을 시작했습니다 : {} 버전 {} 기준, {} 까지", targetSystemId, dryRun.baseVersion(), dryRun.expiresAt());
    }

    private BaselineEstimator.Baselines candidateBaseline(DryRun dryRun) {
        return baselines.effective(dryRun.targetSystemId(), dryRun.candidate().limits());
    }

    private void finish(String targetSystemId, ShadowEvaluation evaluation) {
        try {
            finished.put(targetSystemId, new FinishedDryRun(evaluation.dryRun(), reportOf(evaluation)));
        } catch (RuntimeException e) {
            log.warn("dry-run 마지막 결과를 만들지 못했습니다 : {}", targetSystemId, e);
        }
        stop(targetSystemId, "끝 시각이 지났거나 정책 버전이 바뀌었습니다");
    }

    private void stop(String targetSystemId, String reason) {
        if (running.remove(targetSystemId) != null) {
            log.info("dry-run 을 멈췄습니다 : {} ({})", targetSystemId, reason);
        }
    }

    private void stopExpired(Instant now) {
        running.forEach((targetSystemId, evaluation) -> {
            if (isStale(evaluation.dryRun(), now)) {
                finish(targetSystemId, evaluation);
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

    private record FinishedDryRun(DryRun dryRun, DryRunReport report) {
    }

    public static final class Observation {
        static final Observation NONE = new Observation(null, false);

        private final ShadowEvaluation evaluation;
        private final boolean admitted;

        private Observation(ShadowEvaluation evaluation, boolean admitted) {
            this.evaluation = evaluation;
            this.admitted = admitted;
        }

        public void finish(OutboundResult result) {
            if (evaluation == null) {
                return;
            }

            evaluation.release();
            if (result != null) {
                evaluation.complete(result, Instant.now(), admitted);
            }
        }
    }
}
