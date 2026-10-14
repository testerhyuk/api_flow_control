package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.OutboundCallUseCase;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
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
    private final TargetSystemOutcomeRecorder recorder;
    private final FlowControlMetrics metrics;
    private final DryRunMonitor dryRuns;

    public OutboundCallService(
            TargetSystemClient client,
            TargetSystemSemaphore semaphore,
            TargetSystemRateLimiter rateLimiter,
            TargetSystemOutcomeRecorder recorder,
            FlowControlMetrics metrics,
            DryRunMonitor dryRuns) {
        this.client = client;
        this.semaphore = semaphore;
        this.rateLimiter = rateLimiter;
        this.recorder = recorder;
        this.metrics = metrics;
        this.dryRuns = dryRuns;
    }

    @Override
    public CallAttempt send(OutboundRequest request) {
        String targetSystemId = request.targetSystemId();

        if (!semaphore.tryAcquire(targetSystemId)) {
            metrics.recordBlockedBySemaphore(targetSystemId);
            return new CallAttempt.BlockedBySemaphore();
        }

        try {
            RateLimitResult ratelimit = rateLimiter.tryConsume(targetSystemId);

            if (!ratelimit.allowed()) {
                metrics.recordBlockedByTokenBucket(targetSystemId);
                return new CallAttempt.BlockedByTokenBucket(ratelimit.retryAfter());
            }

            DryRunMonitor.Observation observation = dryRuns.beforeCall(targetSystemId);
            OutboundResult result = null;
            try {
                result = client.call(request);
            } finally {
                observation.finish(result);
            }

            recorder.record(targetSystemId, result.kind(), result.latency());
            metrics.recordCall(targetSystemId, result.kind(), result.latency());

            return new CallAttempt.Called(result);
        } finally {
            semaphore.release(targetSystemId);
        }
    }
}
