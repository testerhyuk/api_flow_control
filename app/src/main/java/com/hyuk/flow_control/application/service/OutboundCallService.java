package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.OutboundCallUseCase;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.TargetSystemClient;
import com.hyuk.flow_control.application.port.out.TargetSystemRateLimiter;
import com.hyuk.flow_control.config.ControlProperties;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.limit.ControlMode;
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
    private final TargetSystemHealthEvaluator evaluator;
    private final BaselineEstimator baselines;
    private final MaintenanceMonitor maintenance;
    private final FlowControlMetrics metrics;
    private final DryRunMonitor dryRuns;
    private final boolean controlled;

    public OutboundCallService(
            TargetSystemClient client,
            TargetSystemSemaphore semaphore,
            TargetSystemRateLimiter rateLimiter,
            TargetSystemOutcomeRecorder recorder,
            TargetSystemHealthEvaluator evaluator,
            BaselineEstimator baselines,
            MaintenanceMonitor maintenance,
            FlowControlMetrics metrics,
            DryRunMonitor dryRuns,
            ControlProperties controlProperties) {
        this.client = client;
        this.semaphore = semaphore;
        this.rateLimiter = rateLimiter;
        this.recorder = recorder;
        this.evaluator = evaluator;
        this.baselines = baselines;
        this.maintenance = maintenance;
        this.metrics = metrics;
        this.dryRuns = dryRuns;
        this.controlled = controlProperties.controlMode() != ControlMode.NONE;
    }

    @Override
    public CallAttempt send(OutboundRequest request) {
        String targetSystemId = request.targetSystemId();

        if (!controlled) {
            return new CallAttempt.Called(call(request));
        }

        if (!semaphore.tryAcquire(targetSystemId)) {
            recorder.recordBlocked(targetSystemId);
            metrics.recordBlockedBySemaphore(targetSystemId);
            return new CallAttempt.BlockedBySemaphore();
        }

        try {
            RateLimitResult ratelimit = rateLimiter.tryConsume(targetSystemId);

            if (!ratelimit.allowed()) {
                recorder.recordBlocked(targetSystemId);
                metrics.recordBlockedByTokenBucket(targetSystemId);
                return new CallAttempt.BlockedByTokenBucket(ratelimit.retryAfter());
            }

            return new CallAttempt.Called(call(request));
        } finally {
            semaphore.release(targetSystemId);
        }
    }

    private OutboundResult call(OutboundRequest request) {
        String targetSystemId = request.targetSystemId();
        DryRunMonitor.Observation observation = dryRuns.beforeCall(targetSystemId);
        OutboundResult result = null;
        boolean excluded = false;
        try {
            result = client.call(request);
            maintenance.onResult(targetSystemId, result);
            excluded = maintenance.excludes(targetSystemId, result.kind());
        } finally {
            observation.finish(excluded ? null : result);
        }

        metrics.recordCall(targetSystemId, result.kind(), result.latency());

        if (excluded) {
            metrics.recordMaintenanceExcluded(targetSystemId);
            return result;
        }

        recorder.record(targetSystemId, result.kind(), result.latency());
        if (evaluator.snapshotOf(targetSystemId).state() == HealthState.HEALTHY) {
            baselines.observe(targetSystemId, result.kind(), result.latency());
        }

        return result;
    }
}
