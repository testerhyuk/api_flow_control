package com.hyuk.flow_control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flow-control.policy")
public record PolicyProperties(
        Duration refreshInterval,
        Duration dryRunMaxDuration
) {
    public PolicyProperties {
        if (refreshInterval == null || !refreshInterval.isPositive()) {
            throw new IllegalArgumentException("refresh-interval 은 0보다 커야 합니다");
        }
        if (dryRunMaxDuration == null || !dryRunMaxDuration.isPositive()) {
            throw new IllegalArgumentException("dry-run-max-duration 은 0보다 커야 합니다");
        }
    }
}
