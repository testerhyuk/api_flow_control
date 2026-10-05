package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.token.RateLimitResult;

public interface TargetSystemRateLimiter {
    RateLimitResult tryConsume(String targetSystemId);
}
