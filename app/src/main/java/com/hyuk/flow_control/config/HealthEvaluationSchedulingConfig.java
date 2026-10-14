package com.hyuk.flow_control.config;

import com.hyuk.flow_control.application.service.DryRunMonitor;
import com.hyuk.flow_control.application.service.TargetSystemHealthEvaluator;
import com.hyuk.flow_control.application.service.TargetSystemLimitAdjuster;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
public class HealthEvaluationSchedulingConfig implements SchedulingConfigurer {
    private final TargetSystemHealthEvaluator evaluator;
    private final TargetSystemLimitAdjuster adjuster;
    private final DryRunMonitor dryRuns;
    private final HealthProperties healthProperties;

    public HealthEvaluationSchedulingConfig(
            TargetSystemHealthEvaluator evaluator,
            TargetSystemLimitAdjuster adjuster,
            DryRunMonitor dryRuns,
            HealthProperties healthProperties) {
        this.evaluator = evaluator;
        this.adjuster = adjuster;
        this.dryRuns = dryRuns;
        this.healthProperties = healthProperties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedRateTask(this::evaluateAndAdjust, healthProperties.evaluationInterval());
    }

    private void evaluateAndAdjust() {
        evaluator.evaluateAll();
        adjuster.adjustAll();
        dryRuns.evaluateAll();
    }
}
