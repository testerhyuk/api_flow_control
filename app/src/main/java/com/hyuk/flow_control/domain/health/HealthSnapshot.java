package com.hyuk.flow_control.domain.health;

import java.time.Duration;
import java.time.Instant;

public record HealthSnapshot(
        HealthState state,
        int sampleCount,
        double failureRate,
        Duration p95,
        Instant evaluatedAt
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
    }

    public static HealthSnapshot initial() {
        return new HealthSnapshot(HealthState.HEALTHY, 0, 0.0, null, null);
    }
}
