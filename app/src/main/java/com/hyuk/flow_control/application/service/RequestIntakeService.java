package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.OutboundCallUseCase;
import com.hyuk.flow_control.application.port.in.RequestIntakeUseCase;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.domain.request.CallAttempt;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RequestIntakeService implements RequestIntakeUseCase {
    static final Duration SEMAPHORE_RETRY_INTERVAL = Duration.ofMillis(10);
    static final Duration MINIMUM_WAIT = Duration.ofMillis(1);

    private final OutboundCallUseCase outboundCall;
    private final FlowControlMetrics metrics;

    public RequestIntakeService(OutboundCallUseCase outboundCall, FlowControlMetrics metrics) {
        this.outboundCall = outboundCall;
        this.metrics = metrics;
    }

    @Override
    public OutboundResult process(OutboundRequest request) {
        long startedAt = System.nanoTime();
        Duration waited = Duration.ZERO;

        while (true) {
            CallAttempt attempt = outboundCall.send(request);

            switch (attempt) {
                case CallAttempt.Called called -> {
                    metrics.recordIntakeWait(request.targetSystemId(), waited);
                    return called.result();
                }
                case CallAttempt.BlockedBySemaphore blocked -> {
                    waitFor(SEMAPHORE_RETRY_INTERVAL);
                    waited = Duration.ofNanos(System.nanoTime() - startedAt);
                }
                case CallAttempt.BlockedByTokenBucket blocked -> {
                    waitFor(blocked.retryAfter());
                    waited = Duration.ofNanos(System.nanoTime() - startedAt);
                }
            }
        }
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
