package com.hyuk.flow_control.domain.expiry;

import java.time.Duration;
import java.time.Instant;

public record RequestTtl(Duration ttl) {
    public RequestTtl {
        if (ttl == null || !ttl.isPositive()) {
            throw new IllegalArgumentException("요청 유효기간은 0보다 커야 합니다");
        }
    }

    public boolean isExpiredAt(Instant createdAt, Instant at) {
        return at.isAfter(createdAt.plus(ttl));
    }

    public static Duration ageAt(Instant createdAt, Instant at) {
        Duration age = Duration.between(createdAt, at);
        return age.isNegative() ? Duration.ZERO : age;
    }
}
