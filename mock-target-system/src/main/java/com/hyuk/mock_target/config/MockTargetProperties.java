package com.hyuk.mock_target.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "mock-target")
public record MockTargetProperties(
        String target,
        Duration duplicateRetention,
        Map<String, TargetSystem> systems
) {
    public MockTargetProperties {
        if (systems == null || systems.isEmpty()) {
            throw new IllegalArgumentException("systems 에 대상이 하나 이상 있어야 합니다");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("실행할 대상 이름(mock-target.target)은 필수입니다");
        }
        if (!systems.containsKey(target)) {
            throw new IllegalArgumentException("systems 에 없는 대상입니다 : " + target);
        }
        if (duplicateRetention == null || !duplicateRetention.isPositive()) {
            throw new IllegalArgumentException("duplicate-retention 은 0보다 커야 합니다");
        }
        systems = Map.copyOf(systems);
    }

    public TargetSystem selected() {
        return systems.get(target);
    }

    public record TargetSystem(
            int port,
            int adminPort,
            int maxConcurrency,
            int queueCapacity,
            long baseProcessingMillis,
            double p95Ratio,
            double fullLoadMultiplier,
            int maxTps,
            Duration rateLimitRetryAfter,
            double serverErrorRate,
            double businessRejectRate,
            ResponseFormat responseFormat
    ) {
        public TargetSystem {
            requirePort(port, "port");
            requirePort(adminPort, "admin-port");
            if (port == adminPort) {
                throw new IllegalArgumentException("port 와 admin-port 는 달라야 합니다");
            }
            if (maxConcurrency <= 0) {
                throw new IllegalArgumentException("max-concurrency 는 0보다 커야 합니다");
            }
            if (queueCapacity < 0) {
                throw new IllegalArgumentException("queue-capacity 는 0 이상이어야 합니다");
            }
            if (baseProcessingMillis <= 0) {
                throw new IllegalArgumentException("base-processing-millis 는 0보다 커야 합니다");
            }
            if (p95Ratio < 1) {
                throw new IllegalArgumentException("p95-ratio 는 1 이상이어야 합니다");
            }
            if (fullLoadMultiplier < 1) {
                throw new IllegalArgumentException("full-load-multiplier 는 1 이상이어야 합니다");
            }
            if (maxTps <= 0) {
                throw new IllegalArgumentException("max-tps 는 0보다 커야 합니다");
            }
            if (rateLimitRetryAfter == null || rateLimitRetryAfter.isNegative()) {
                throw new IllegalArgumentException("rate-limit-retry-after 는 0 이상이어야 합니다");
            }
            requireRate(serverErrorRate, "server-error-rate");
            requireRate(businessRejectRate, "business-reject-rate");
            if (serverErrorRate + businessRejectRate > 1) {
                throw new IllegalArgumentException("server-error-rate 와 business-reject-rate 의 합은 1 이하여야 합니다");
            }
            if (responseFormat == null) {
                throw new IllegalArgumentException("response-format 은 필수입니다");
            }
        }

        private static void requirePort(int value, String name) {
            if (value <= 0 || value > 65535) {
                throw new IllegalArgumentException(name + " 는 1~65535 사이여야 합니다");
            }
        }

        private static void requireRate(double value, String name) {
            if (value < 0 || value > 1) {
                throw new IllegalArgumentException(name + " 는 0 이상 1 이하여야 합니다");
            }
        }
    }
}
