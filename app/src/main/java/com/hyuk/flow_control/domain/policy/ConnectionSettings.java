package com.hyuk.flow_control.domain.policy;

import java.time.Duration;

public record ConnectionSettings(
        String url,
        String path,
        String probePath,
        Duration connectTimeout,
        Duration readTimeout
) {
    public static final String DEFAULT_PATH = "/call";
    public static final String DEFAULT_PROBE_PATH = "/health";

    public ConnectionSettings {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 은 필수입니다");
        }
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("path 는 / 로 시작해야 합니다");
        }
        if (probePath == null || !probePath.startsWith("/")) {
            throw new IllegalArgumentException("probe-path 는 / 로 시작해야 합니다");
        }
        if (connectTimeout == null || !connectTimeout.isPositive()) {
            throw new IllegalArgumentException("connect-timeout 은 0보다 커야 합니다");
        }
        if (readTimeout == null || !readTimeout.isPositive()) {
            throw new IllegalArgumentException("read-timeout 은 0보다 커야 합니다");
        }
    }

    public ConnectionSettings(String url, String path, Duration connectTimeout, Duration readTimeout) {
        this(url, path, DEFAULT_PROBE_PATH, connectTimeout, readTimeout);
    }

    public ConnectionSettings(String url, Duration connectTimeout, Duration readTimeout) {
        this(url, DEFAULT_PATH, connectTimeout, readTimeout);
    }
}
