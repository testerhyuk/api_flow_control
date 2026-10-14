package com.hyuk.flow_control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flow-control.shared-limit")
public record SharedLimitProperties(
        Duration proposalTtl,
        Duration redisTimeout,
        Duration recheckInterval
) {
    public SharedLimitProperties {
        if (proposalTtl == null || !proposalTtl.isPositive()) {
            throw new IllegalArgumentException("proposal-ttl 은 0보다 커야 합니다");
        }
        if (redisTimeout == null || !redisTimeout.isPositive()) {
            throw new IllegalArgumentException("redis-timeout 은 0보다 커야 합니다");
        }
        if (recheckInterval == null || !recheckInterval.isPositive()) {
            throw new IllegalArgumentException("recheck-interval 은 0보다 커야 합니다");
        }
    }
}
