package com.hyuk.flow_control.domain.token;

import java.time.Duration;

public record RateLimitResult(
        boolean allowed,
        Duration retryAfter
) {
    public RateLimitResult {
        if (retryAfter == null || retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter는 0 이상이어야 합니다");
        }

        if (allowed && !retryAfter.isZero()) {
            throw new IllegalArgumentException("통과한 경우 retryAfter는 0이어야 합니다");
        }
    }

    public static RateLimitResult allow() {
        return new RateLimitResult(true, Duration.ZERO);
    }

    public static RateLimitResult reject(Duration retryAfter) {
        return new RateLimitResult(false, retryAfter);
    }
}
