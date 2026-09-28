package com.hyuk.flow_control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "flow-control")
public record TargetSystemProperties(
        Map<String, TargetSystem> targetSystems
) {
    public TargetSystemProperties {
        if (targetSystems == null || targetSystems.isEmpty()) {
            throw new IllegalArgumentException("대상 시스템이 설정되지 않았습니다");
        }
    }

    public record TargetSystem(
            String url,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        public TargetSystem {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("url 은 필수입니다");
            }
            if (connectTimeout == null || !connectTimeout.isPositive()) {
                throw new IllegalArgumentException("connect-timeout 은 0보다 커야 합니다");
            }
            if (readTimeout == null || !readTimeout.isPositive()) {
                throw new IllegalArgumentException("read-timeout 은 0보다 커야 합니다");
            }
        }
    }
}
