package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.OutboundCallUseCase;
import com.hyuk.flow_control.application.port.out.TargetSystemClient;
import com.hyuk.flow_control.application.port.out.TargetSystemRateLimiter;
import com.hyuk.flow_control.domain.request.CallAttempt;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.token.RateLimitResult;
import org.springframework.stereotype.Service;

@Service
public class OutboundCallService implements OutboundCallUseCase {
    private final TargetSystemClient client;
    private final TargetSystemSemaphore semaphore;
    private final TargetSystemRateLimiter rateLimiter;

    public OutboundCallService(
            TargetSystemClient client,
            TargetSystemSemaphore semaphore,
            TargetSystemRateLimiter rateLimiter) {
        this.client = client;
        this.semaphore = semaphore;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public CallAttempt send(OutboundRequest request) {
        String targetSystemId = request.targetSystemId();

        if (!semaphore.tryAcquire(targetSystemId)) {
            return new CallAttempt.BlockedBySemaphore();
        }

        try {
            RateLimitResult ratelimit = rateLimiter.tryConsume(targetSystemId);

            if (!ratelimit.allowed()) {
                return new CallAttempt.BlockedByTokenBucket(ratelimit.retryAfter());
            }

            OutboundResult result = client.call(request);

            return new CallAttempt.Called(result);
        } finally {
            semaphore.release(targetSystemId);
        }
    }
}
