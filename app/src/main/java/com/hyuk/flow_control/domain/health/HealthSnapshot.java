package com.hyuk.flow_control.domain.health;

import java.time.Duration;
import java.time.Instant;

public record HealthSnapshot(
        HealthState state,
        int sampleCount,
        double failureRate,
        Duration p95,
        Instant evaluatedAt,
        HealthMode mode,
        Duration windowSpan,
        int degradedStreak,
        Duration baselineRtt
) {
    public HealthSnapshot {
        if (state == null) {
            throw new IllegalArgumentException("state는 필수입니다");
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount는 0 이상이어야 합니다");
        }
        if (failureRate < 0 || failureRate > 1) {
            throw new IllegalArgumentException("failureRate는 0 이상 1 이하여야 합니다");
        }
        if (degradedStreak < 0) {
            throw new IllegalArgumentException("degradedStreak는 0 이상이어야 합니다");
        }
        if (mode == null) {
            mode = HealthMode.ADAPTIVE;
        }
    }

    public HealthSnapshot(HealthState state, int sampleCount, double failureRate, Duration p95, Instant evaluatedAt) {
        this(state, sampleCount, failureRate, p95, evaluatedAt, HealthMode.ADAPTIVE, null, 0, null);
    }

    public static HealthSnapshot initial() {
        return new HealthSnapshot(HealthState.HEALTHY, 0, 0.0, null, null);
    }

    public HealthSnapshot withState(HealthState newState) {
        return new HealthSnapshot(newState, sampleCount, failureRate, p95, evaluatedAt, mode, windowSpan,
                degradedStreak, baselineRtt);
    }
}
