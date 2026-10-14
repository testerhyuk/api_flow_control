package com.hyuk.flow_control.domain.retry;

import com.hyuk.flow_control.domain.expiry.ExpiryPoint;
import com.hyuk.flow_control.domain.request.OutcomeKind;

public sealed interface HandlingOutcome {
    record Completed(OutcomeKind kind) implements HandlingOutcome {}

    record Discarded(OutcomeKind kind) implements HandlingOutcome {}

    record RetryScheduled(RetryStage stage, OutcomeKind kind) implements HandlingOutcome {}

    record DeadLettered(DeadLetterReason reason) implements HandlingOutcome {}

    record Expired(ExpiryPoint where) implements HandlingOutcome {}
}
