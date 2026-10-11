package com.hyuk.flow_control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flow-control.limit-adjustment")
public record LimitAdjustmentProperties(
        double degradedRatio,
        double recoveryStep
) {
    public LimitAdjustmentProperties {
        if (degradedRatio <= 0 || degradedRatio >= 1) {
            throw new IllegalArgumentException("degraded-ratio 는 0보다 크고 1보다 작아야 합니다");
        }
        if (recoveryStep <= 0 || recoveryStep > 1) {
            throw new IllegalArgumentException("recovery-step 은 0보다 크고 1 이하여야 합니다");
        }
    }
}
