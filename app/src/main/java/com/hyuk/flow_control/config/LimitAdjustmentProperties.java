package com.hyuk.flow_control.config;

import com.hyuk.flow_control.application.settings.LimitAdjustmentSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flow-control.limit-adjustment")
public record LimitAdjustmentProperties(
        double degradedRatio
) {
    public LimitAdjustmentProperties {
        if (degradedRatio <= 0 || degradedRatio >= 1) {
            throw new IllegalArgumentException("degraded-ratio 는 0보다 크고 1보다 작아야 합니다");
        }
    }

    public LimitAdjustmentSettings settings() {
        return new LimitAdjustmentSettings(degradedRatio);
    }
}
