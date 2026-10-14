package com.hyuk.flow_control.domain.policy;

import java.time.Duration;

public record ConnectionSettings(
        String url,
        Duration connectTimeout,
        Duration readTimeout
) {
    public ConnectionSettings {
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
