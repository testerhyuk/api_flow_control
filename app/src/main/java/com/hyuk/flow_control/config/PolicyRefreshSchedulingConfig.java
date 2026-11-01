package com.hyuk.flow_control.config;

import com.hyuk.flow_control.application.service.DryRunMonitor;
import com.hyuk.flow_control.application.service.PolicyRegistry;
import com.hyuk.flow_control.application.service.RequesterRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
public class PolicyRefreshSchedulingConfig implements SchedulingConfigurer {
    private final PolicyRegistry registry;
    private final RequesterRegistry requesters;
    private final DryRunMonitor dryRuns;
    private final PolicyProperties policyProperties;

    public PolicyRefreshSchedulingConfig(PolicyRegistry registry, RequesterRegistry requesters, DryRunMonitor dryRuns,
                                         PolicyProperties policyProperties) {
        this.registry = registry;
        this.requesters = requesters;
        this.dryRuns = dryRuns;
        this.policyProperties = policyProperties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::refresh, policyProperties.refreshInterval());
    }

    private void refresh() {
        registry.refresh();
        requesters.refresh();
        dryRuns.refresh();
    }
}
