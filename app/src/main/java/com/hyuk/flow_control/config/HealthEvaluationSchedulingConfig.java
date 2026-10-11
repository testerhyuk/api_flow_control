package com.hyuk.flow_control.config;

import com.hyuk.flow_control.application.service.TargetSystemHealthEvaluator;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
public class HealthEvaluationSchedulingConfig implements SchedulingConfigurer {
    private final TargetSystemHealthEvaluator evaluator;
    private final HealthProperties healthProperties;

    public HealthEvaluationSchedulingConfig(
            TargetSystemHealthEvaluator evaluator,
            HealthProperties healthProperties) {
        this.evaluator = evaluator;
        this.healthProperties = healthProperties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedRateTask(evaluator::evaluateAll, healthProperties.evaluationInterval());
    }
}
