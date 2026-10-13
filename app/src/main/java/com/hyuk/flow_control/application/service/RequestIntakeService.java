package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.OutboundCallUseCase;
import com.hyuk.flow_control.application.port.in.RequestIntakeUseCase;
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

    public RequestIntakeService(OutboundCallUseCase outboundCall) {
        this.outboundCall = outboundCall;
    }

    @Override
    public OutboundResult process(OutboundRequest request) {
        while (true) {
            CallAttempt attempt = outboundCall.send(request);

            switch (attempt) {
                case CallAttempt.Called called -> {
                    return called.result();
                }
                case CallAttempt.BlockedBySemaphore blocked -> waitFor(SEMAPHORE_RETRY_INTERVAL);
                case CallAttempt.BlockedByTokenBucket blocked -> waitFor(blocked.retryAfter());
            }
        }
    }

    private void waitFor(Duration duration) {
        try {
            Thread.sleep(duration.compareTo(MINIMUM_WAIT) < 0 ? MINIMUM_WAIT : duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("요청이 한도 대기 중에 중단되었습니다", e);
        }
    }
}
