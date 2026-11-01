package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.OutboundCallUseCase;
import com.hyuk.flow_control.application.port.in.RequestIntakeUseCase;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.domain.request.CallAttempt;
import com.hyuk.flow_control.domain.request.IntakeResult;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class RequestIntakeService implements RequestIntakeUseCase {
    static final Duration MINIMUM_WAIT = Duration.ofMillis(1);

    private final OutboundCallUseCase outboundCall;
    private final TargetSystemSemaphore semaphore;
    private final TargetSystemOutcomeRecorder recorder;
    private final FlowControlMetrics metrics;
    private final DryRunMonitor dryRuns;

    public RequestIntakeService(
            OutboundCallUseCase outboundCall,
            TargetSystemSemaphore semaphore,
            TargetSystemOutcomeRecorder recorder,
            FlowControlMetrics metrics,
            DryRunMonitor dryRuns) {
        this.outboundCall = outboundCall;
        this.semaphore = semaphore;
        this.recorder = recorder;
        this.metrics = metrics;
        this.dryRuns = dryRuns;
    }

    @Override
    public IntakeResult process(OutboundRequest request, Instant deadline) {
        String targetSystemId = request.targetSystemId();
        long startedAt = System.nanoTime();
        Duration waited = Duration.ZERO;

        while (true) {
            if (passed(deadline)) {
                return new IntakeResult.Expired(Instant.now());
            }

            CallAttempt attempt = outboundCall.send(request);

            switch (attempt) {
                case CallAttempt.Called called -> {
                    metrics.recordIntakeWait(targetSystemId, waited);
                    if (waited.isPositive()) {
                        dryRuns.recordWaited(targetSystemId);
                    }
                    return new IntakeResult.Called(called.result());
                }
                case CallAttempt.BlockedBySemaphore blocked -> {
                    if (!awaitSemaphore(targetSystemId, deadline)) {
                        return new IntakeResult.Expired(Instant.now());
                    }
                    waited = Duration.ofNanos(System.nanoTime() - startedAt);
                }
                case CallAttempt.BlockedByTokenBucket blocked -> {
                    waitFor(capped(blocked.retryAfter(), deadline));
                    waited = Duration.ofNanos(System.nanoTime() - startedAt);
                }
            }
        }
    }

    private boolean awaitSemaphore(String targetSystemId, Instant deadline) {
        recorder.waitStarted(targetSystemId);
        try {
            return semaphore.awaitAvailable(targetSystemId, deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntakeInterruptedException(e);
        } finally {
            recorder.waitEnded(targetSystemId);
        }
    }

    private static boolean passed(Instant deadline) {
        return deadline != null && Instant.now().isAfter(deadline);
    }

    private static Duration capped(Duration wait, Instant deadline) {
        if (deadline == null) {
            return wait;
        }

        Duration untilDeadline = Duration.between(Instant.now(), deadline);
        return untilDeadline.compareTo(wait) < 0 ? untilDeadline : wait;
    }

    private void waitFor(Duration duration) {
        try {
            Thread.sleep(duration.compareTo(MINIMUM_WAIT) < 0 ? MINIMUM_WAIT : duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntakeInterruptedException(e);
        }
    }
}
