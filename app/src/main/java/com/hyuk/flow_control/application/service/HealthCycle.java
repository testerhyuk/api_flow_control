package com.hyuk.flow_control.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class HealthCycle {
    private static final Logger log = LoggerFactory.getLogger(HealthCycle.class);

    private final MaintenanceMonitor maintenance;
    private final BaselineEstimator baselines;
    private final TargetSystemHealthEvaluator evaluator;
    private final TargetSystemLimitAdjuster adjuster;
    private final DryRunMonitor dryRuns;

    public HealthCycle(
            MaintenanceMonitor maintenance,
            BaselineEstimator baselines,
            TargetSystemHealthEvaluator evaluator,
            TargetSystemLimitAdjuster adjuster,
            DryRunMonitor dryRuns) {
        this.maintenance = maintenance;
        this.baselines = baselines;
        this.evaluator = evaluator;
        this.adjuster = adjuster;
        this.dryRuns = dryRuns;
    }

    public void tick() {
        Set<String> ended = maintenance.tick();
        ended.forEach(targetSystemId -> {
            try {
                evaluator.onMaintenanceEnded(targetSystemId);
                adjuster.onMaintenanceEnded(targetSystemId);
            } catch (RuntimeException e) {
                log.warn("점검 종료 처리 실패 : {}", targetSystemId, e);
            }
        });

        baselines.refresh();
        evaluator.evaluateAll();
        adjuster.adjustAll();
        dryRuns.evaluateAll();
    }
}
