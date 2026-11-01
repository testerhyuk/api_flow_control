package com.hyuk.flow_control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flow-control.maintenance")
public record MaintenanceProperties(
        Duration probeInterval
) {
    public MaintenanceProperties {
        if (probeInterval == null || !probeInterval.isPositive()) {
            throw new IllegalArgumentException("probe-interval 은 0보다 커야 합니다");
        }
    }
}
