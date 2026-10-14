package com.hyuk.flow_control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flow-control.intake")
public record IntakeProperties(
        Duration requestTtl,
        Duration lagInterval
) {
    public IntakeProperties {
        if (requestTtl == null || !requestTtl.isPositive()) {
            throw new IllegalArgumentException("request-ttl 은 0보다 커야 합니다");
        }
        if (lagInterval == null || !lagInterval.isPositive()) {
            throw new IllegalArgumentException("lag-interval 은 0보다 커야 합니다");
        }
    }
}
