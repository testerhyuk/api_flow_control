package com.hyuk.flow_control.domain.result;

import com.hyuk.flow_control.domain.expiry.ExpiryPoint;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.retry.DeadLetterReason;

import java.time.Instant;

public record RequestResult(
        String requestId,
        String targetSystemId,
        FinalOutcome outcome,
        OutcomeKind kind,
        Integer statusCode,
        String resultCode,
        String responseBody,
        DeadLetterReason deadLetterReason,
        ExpiryPoint expiredWhere,
        int attempts,
        Instant finishedAt,
        String rawMessage
) {
    public RequestResult {
        if (targetSystemId == null || targetSystemId.isBlank()) {
            throw new IllegalArgumentException("targetSystemId 는 필수입니다");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 은 필수입니다");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts 는 0 이상이어야 합니다");
        }
        if (finishedAt == null) {
            throw new IllegalArgumentException("finishedAt 은 필수입니다");
        }
    }

    public static RequestResult called(String requestId, String targetSystemId, FinalOutcome outcome,
                                       OutboundResult result, int attempts, Instant finishedAt) {
        return new RequestResult(requestId, targetSystemId, outcome, result.kind(), result.statusCode(),
                result.resultCode(), result.responseBody(), null, null, attempts, finishedAt, null);
    }

    public static RequestResult deadLettered(String requestId, String targetSystemId, DeadLetterReason reason,
                                             OutboundResult lastResult, int attempts, Instant finishedAt) {
        if (lastResult == null) {
            return new RequestResult(requestId, targetSystemId, FinalOutcome.DEAD_LETTERED, null, null, null, null,
                    reason, null, attempts, finishedAt, null);
        }
        return new RequestResult(requestId, targetSystemId, FinalOutcome.DEAD_LETTERED, lastResult.kind(),
                lastResult.statusCode(), lastResult.resultCode(), lastResult.responseBody(), reason, null, attempts,
                finishedAt, null);
    }

    public static RequestResult invalidMessage(String targetSystemId, String rawMessage, Instant finishedAt) {
        return new RequestResult(null, targetSystemId, FinalOutcome.DEAD_LETTERED, null, null, null, null,
                DeadLetterReason.INVALID_MESSAGE, null, 0, finishedAt, rawMessage);
    }

    public static RequestResult expired(String requestId, String targetSystemId, ExpiryPoint where,
                                        OutcomeKind lastKind, int attempts, Instant finishedAt) {
        return new RequestResult(requestId, targetSystemId, FinalOutcome.EXPIRED, lastKind, null, null, null, null,
                where, attempts, finishedAt, null);
    }

    public static RequestResult expiredAfterCall(String requestId, String targetSystemId, ExpiryPoint where,
                                                 OutboundResult lastResult, int attempts, Instant finishedAt) {
        return new RequestResult(requestId, targetSystemId, FinalOutcome.EXPIRED, lastResult.kind(),
                lastResult.statusCode(), lastResult.resultCode(), lastResult.responseBody(), null, where, attempts,
                finishedAt, null);
    }
}
