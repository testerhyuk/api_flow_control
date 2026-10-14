package com.hyuk.flow_control.config;

import com.hyuk.flow_control.application.service.HealthCycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
public class HealthEvaluationSchedulingConfig implements SchedulingConfigurer {
    private final HealthCycle cycle;
    private final HealthProperties healthProperties;

    public HealthEvaluationSchedulingConfig(HealthCycle cycle, HealthProperties healthProperties) {
        this.cycle = cycle;
        this.healthProperties = healthProperties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedRateTask(cycle::tick, healthProperties.evaluationInterval());
    }
}
