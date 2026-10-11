package com.hyuk.flow_control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "flow-control.health")
public record HealthProperties(
        Duration window,
        Duration evaluationInterval,
        int minimumCalls,
        double degradedFailureRate,
        double criticalFailureRate,
        double degradedLatencyMultiplier,
        Duration recoveryDuration
) {
    public HealthProperties {
        if (window == null || !window.isPositive()) {
            throw new IllegalArgumentException("window 는 0보다 커야 합니다");
        }
        if (evaluationInterval == null || !evaluationInterval.isPositive()) {
            throw new IllegalArgumentException("evaluation-interval 은 0보다 커야 합니다");
        }
        if (minimumCalls <= 0) {
            throw new IllegalArgumentException("minimum-calls 는 0보다 커야 합니다");
        }
        if (degradedFailureRate <= 0 || degradedFailureRate >= criticalFailureRate) {
            throw new IllegalArgumentException("degraded-failure-rate 는 0보다 크고 critical-failure-rate 보다 작아야 합니다");
        }
        if (criticalFailureRate > 1) {
            throw new IllegalArgumentException("critical-failure-rate 는 1 이하여야 합니다");
        }
        if (degradedLatencyMultiplier <= 1) {
            throw new IllegalArgumentException("degraded-latency-multiplier 는 1보다 커야 합니다");
        }
        if (recoveryDuration == null || !recoveryDuration.isPositive()) {
            throw new IllegalArgumentException("recovery-duration 은 0보다 커야 합니다");
        }
        if (recoveryDuration.compareTo(evaluationInterval) <= 0) {
            throw new IllegalArgumentException("recovery-duration 은 evaluation-interval 보다 길어야 합니다");
        }
    }
}
