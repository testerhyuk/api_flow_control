package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.IntakeInterruptedException;
import com.hyuk.flow_control.application.port.in.RequestHandlingUseCase;
import com.hyuk.flow_control.application.port.in.RequestIntakeUseCase;
import com.hyuk.flow_control.application.port.out.FailedRequestPublisher;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.ResultPublisher;
import com.hyuk.flow_control.config.ControlProperties;
import com.hyuk.flow_control.config.IntakeProperties;
import com.hyuk.flow_control.domain.expiry.ExpiryPoint;
import com.hyuk.flow_control.domain.expiry.RequestTtl;
import com.hyuk.flow_control.domain.limit.ControlMode;
import com.hyuk.flow_control.domain.request.IntakeResult;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.result.FinalOutcome;
import com.hyuk.flow_control.domain.result.RequestResult;
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
    private final ResultPublisher results;
    private final FlowControlMetrics metrics;
    private final RequestTtl ttl;
    private final boolean maintenanceAware;

    public RequestHandlingService(
            RequestIntakeUseCase intake,
            FailedRequestPublisher publisher,
            ResultPublisher results,
            FlowControlMetrics metrics,
            IntakeProperties intakeProperties,
            ControlProperties controlProperties) {
        this.intake = intake;
        this.publisher = publisher;
        this.results = results;
        this.metrics = metrics;
        this.ttl = new RequestTtl(intakeProperties.requestTtl());
        this.maintenanceAware = controlProperties.controlMode() == ControlMode.ADAPTIVE;
    }

    @Override
    public HandlingOutcome handle(OutboundRequest request, DeliveryContext context) {
        Instant takenAt = Instant.now();

        if (request.createdAt() != null) {
            metrics.recordRequestAge(request.targetSystemId(), RequestTtl.ageAt(request.createdAt(), takenAt));

            if (ttl.isExpiredAt(request.createdAt(), takenAt)) {
                return expire(request, context, ExpiryPoint.INTAKE, context.lastKind(), takenAt);
            }
        }

        Instant deadline = request.createdAt() == null ? null : ttl.deadlineOf(request.createdAt());
        OutboundResult result;

        try {
            switch (intake.process(request, deadline)) {
                case IntakeResult.Called called -> result = called.result();
                case IntakeResult.Expired expired -> {
                    return expire(request, context, ExpiryPoint.LIMIT_WAIT, context.lastKind(), expired.at());
                }
            }
        } catch (IntakeInterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("요청 처리 중 예외가 났습니다 : {} {} {}",
                    request.targetSystemId(), request.requestId(), context.origin(), e);
            return deadLetter(request, context.afterFailure(Instant.now()),
                    DeadLetterReason.PROCESSING_ERROR, null, describe(e));
        }

        OutcomeKind kind = result.kind();

        return switch (Disposition.of(kind)) {
            case COMPLETE -> complete(request, context, result);
            case DISCARD -> discard(request, context, result);
            case RETRY -> retry(request, context.afterFailure(Instant.now()), result);
            case DEAD_LETTER -> deadLetter(request, context.afterFailure(Instant.now()),
                    DeadLetterReason.DLQ_KIND, result, null);
        };
    }

    @Override
    public HandlingOutcome rejectInvalid(String targetSystemId, String rawValue, DeliveryContext context, String reason) {
        publisher.deadLetterRaw(targetSystemId, rawValue, context.afterFailure(Instant.now()), reason);
        metrics.recordDeadLetter(targetSystemId, DeadLetterReason.INVALID_MESSAGE);
        log.warn("잘못된 요청 메시지를 DLQ 로 보냈습니다 : {} {} ({})", targetSystemId, context.origin(), reason);
        report(context, RequestResult.invalidMessage(targetSystemId, rawValue, Instant.now()));

        return new HandlingOutcome.DeadLettered(DeadLetterReason.INVALID_MESSAGE);
    }

    private HandlingOutcome complete(OutboundRequest request, DeliveryContext context, OutboundResult result) {
        if (context.isRetry()) {
            metrics.recordRetrySucceeded(request.targetSystemId(), context.stage());
        }
        report(context, RequestResult.called(request.requestId(), request.targetSystemId(), FinalOutcome.COMPLETED,
                result, context.failedAttempts() + 1, Instant.now()));
        return new HandlingOutcome.Completed(result.kind());
    }

    private HandlingOutcome discard(OutboundRequest request, DeliveryContext context, OutboundResult result) {
        log.info("요청을 폐기합니다 : {} {} {}", request.targetSystemId(), request.requestId(), result.kind());
        report(context, RequestResult.called(request.requestId(), request.targetSystemId(), FinalOutcome.DISCARDED,
                result, context.failedAttempts() + 1, Instant.now()));
        return new HandlingOutcome.Discarded(result.kind());
    }

    private HandlingOutcome retry(OutboundRequest request, DeliveryContext failed, OutboundResult result) {
        OutcomeKind kind = result.kind();
        Optional<RetryStage> next = RetryStage.firstAfter(failed.stage(), minimumDelayOf(result));

        if (next.isEmpty()) {
            return deadLetter(request, failed, DeadLetterReason.RETRY_EXHAUSTED, result, null);
        }

        Instant now = Instant.now();
        if (request.createdAt() != null && ttl.isExpiredAt(request.createdAt(), now.plus(next.get().delay()))) {
            return expire(request, failed, ExpiryPoint.RETRY_SCHEDULE, kind, now,
                    RequestResult.expiredAfterCall(request.requestId(), request.targetSystemId(),
                            ExpiryPoint.RETRY_SCHEDULE, result, failed.failedAttempts(), now));
        }

        publisher.scheduleRetry(request, failed.movedTo(next.get()), kind);
        metrics.recordRetryScheduled(request.targetSystemId(), next.get(), kind);

        return new HandlingOutcome.RetryScheduled(next.get(), kind);
    }

    private HandlingOutcome deadLetter(OutboundRequest request, DeliveryContext failed, DeadLetterReason reason,
                                       OutboundResult lastResult, String error) {
        OutcomeKind kind = lastResult == null ? null : lastResult.kind();
        publisher.deadLetter(request, failed, reason, lastResult, error);
        metrics.recordDeadLetter(request.targetSystemId(), reason);
        log.warn("요청을 DLQ 로 보냈습니다 : {} {} reason={} kind={} attempts={}",
                request.targetSystemId(), request.requestId(), reason.label(), kind, failed.failedAttempts());
        report(failed, RequestResult.deadLettered(request.requestId(), request.targetSystemId(), reason, lastResult,
                failed.failedAttempts(), Instant.now()));

        return new HandlingOutcome.DeadLettered(reason);
    }

    private HandlingOutcome expire(OutboundRequest request, DeliveryContext context, ExpiryPoint where,
                                   OutcomeKind lastKind, Instant at) {
        return expire(request, context, where, lastKind, at, RequestResult.expired(request.requestId(),
                request.targetSystemId(), where, lastKind, context.failedAttempts(), at));
    }

    private HandlingOutcome expire(OutboundRequest request, DeliveryContext context, ExpiryPoint where,
                                   OutcomeKind lastKind, Instant at, RequestResult result) {
        Duration age = RequestTtl.ageAt(request.createdAt(), at);
        publisher.expire(request, context, where, lastKind, age);
        metrics.recordExpired(request.targetSystemId(), where);
        log.debug("유효기간이 지난 요청을 만료 토픽으로 보냈습니다 : {} {} where={} age={}s attempts={}",
                request.targetSystemId(), request.requestId(), where.label(), age.toSeconds(), context.failedAttempts());
        report(context, result);

        return new HandlingOutcome.Expired(where);
    }

    private void report(DeliveryContext context, RequestResult result) {
        if (context.requesterId() == null) {
            log.warn("요청자를 알 수 없어 결과를 보내지 않습니다 : {} {} {} {}", result.targetSystemId(),
                    result.requestId(), result.outcome().label(), context.origin());
            return;
        }
        results.publish(context.requesterId(), result);
    }

    private Duration minimumDelayOf(OutboundResult result) {
        if (maintenanceAware && result.kind() == OutcomeKind.UNDER_MAINTENANCE) {
            return Duration.ZERO;
        }
        return minimumDelay(result.retryAfter());
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
