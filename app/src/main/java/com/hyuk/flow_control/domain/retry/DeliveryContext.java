package com.hyuk.flow_control.domain.retry;

import java.time.Instant;

public record DeliveryContext(
        RetryStage stage,
        int failedAttempts,
        Instant firstFailedAt,
        Instant lastFailedAt,
        String origin
) {
    public DeliveryContext {
        if (failedAttempts < 0) {
            throw new IllegalArgumentException("failedAttempts 는 0 이상이어야 합니다");
        }
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("origin 은 필수입니다");
        }
    }

    public static DeliveryContext first(String origin) {
        return new DeliveryContext(null, 0, null, null, origin);
    }

    public boolean isRetry() {
        return stage != null;
    }

    public DeliveryContext afterFailure(Instant failedAt) {
        return new DeliveryContext(
                stage,
                failedAttempts + 1,
                firstFailedAt == null ? failedAt : firstFailedAt,
                failedAt,
                origin);
    }

    public DeliveryContext movedTo(RetryStage next) {
        return new DeliveryContext(next, failedAttempts, firstFailedAt, lastFailedAt, origin);
    }
}
