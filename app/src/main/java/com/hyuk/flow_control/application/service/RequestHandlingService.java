package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.RequestHandlingUseCase;
import com.hyuk.flow_control.application.port.in.RequestIntakeUseCase;
import com.hyuk.flow_control.application.port.out.FailedRequestPublisher;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.retry.DeadLetterReason;
import com.hyuk.flow_control.domain.retry.DeliveryContext;
import com.hyuk.flow_control.domain.retry.Disposition;
import com.hyuk.flow_control.domain.retry.HandlingOutcome;
import com.hyuk.flow_control.domain.retry.RetryStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class RequestHandlingService implements RequestHandlingUseCase {
    private static final Logger log = LoggerFactory.getLogger(RequestHandlingService.class);

    private final RequestIntakeUseCase intake;
    private final FailedRequestPublisher publisher;
    private final FlowControlMetrics metrics;

    public RequestHandlingService(
            RequestIntakeUseCase intake,
            FailedRequestPublisher publisher,
            FlowControlMetrics metrics) {
        this.intake = intake;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    @Override
    public HandlingOutcome handle(OutboundRequest request, DeliveryContext context) {
        OutboundResult result;

        try {
            result = intake.process(request);
        } catch (IntakeInterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            return deadLetter(request, context.afterFailure(Instant.now()),
                    DeadLetterReason.PROCESSING_ERROR, null, describe(e));
        }

        OutcomeKind kind = result.kind();

        return switch (Disposition.of(kind)) {
            case COMPLETE -> complete(request, context, kind);
            case DISCARD -> discard(request, kind);
            case RETRY -> retry(request, context.afterFailure(Instant.now()), kind, result.retryAfter());
            case DEAD_LETTER -> deadLetter(request, context.afterFailure(Instant.now()),
                    DeadLetterReason.DLQ_KIND, kind, null);
        };
    }

    @Override
    public HandlingOutcome rejectInvalid(String targetSystemId, String rawValue, DeliveryContext context, String reason) {
        publisher.deadLetterRaw(targetSystemId, rawValue, context.afterFailure(Instant.now()), reason);
        metrics.recordDeadLetter(targetSystemId, DeadLetterReason.INVALID_MESSAGE);
        log.warn("잘못된 요청 메시지를 DLQ 로 보냈습니다 : {} {} ({})", targetSystemId, context.origin(), reason);

        return new HandlingOutcome.DeadLettered(DeadLetterReason.INVALID_MESSAGE);
    }

    private HandlingOutcome complete(OutboundRequest request, DeliveryContext context, OutcomeKind kind) {
        if (context.isRetry()) {
            metrics.recordRetrySucceeded(request.targetSystemId(), context.stage());
        }
        return new HandlingOutcome.Completed(kind);
    }

    private HandlingOutcome discard(OutboundRequest request, OutcomeKind kind) {
        log.info("요청을 폐기합니다 : {} {} {}", request.targetSystemId(), request.requestId(), kind);
        return new HandlingOutcome.Discarded(kind);
    }

    private HandlingOutcome retry(OutboundRequest request, DeliveryContext failed, OutcomeKind kind, Instant retryAfter) {
        Optional<RetryStage> next = RetryStage.firstAfter(failed.stage(), minimumDelay(retryAfter));

        if (next.isEmpty()) {
            return deadLetter(request, failed, DeadLetterReason.RETRY_EXHAUSTED, kind, null);
        }

        publisher.scheduleRetry(request, failed.movedTo(next.get()), kind);
        metrics.recordRetryScheduled(request.targetSystemId(), next.get(), kind);

        return new HandlingOutcome.RetryScheduled(next.get(), kind);
    }

    private HandlingOutcome deadLetter(OutboundRequest request, DeliveryContext failed, DeadLetterReason reason,
                                       OutcomeKind kind, String error) {
        publisher.deadLetter(request, failed, reason, kind, error);
        metrics.recordDeadLetter(request.targetSystemId(), reason);
        log.warn("요청을 DLQ 로 보냈습니다 : {} {} reason={} kind={} attempts={}",
                request.targetSystemId(), request.requestId(), reason.label(), kind, failed.failedAttempts());

        return new HandlingOutcome.DeadLettered(reason);
    }

    private static Duration minimumDelay(Instant retryAfter) {
        if (retryAfter == null) {
            return Duration.ZERO;
        }

        Duration remaining = Duration.between(Instant.now(), retryAfter);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    private static String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
