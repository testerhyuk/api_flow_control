package com.hyuk.flow_control.adapter.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hyuk.flow_control.domain.result.RequestResult;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultMessage(
        String requestId,
        String targetSystemId,
        String outcome,
        String kind,
        Integer statusCode,
        String resultCode,
        String responseBody,
        String deadLetterReason,
        String expiredWhere,
        int attempts,
        Instant finishedAt,
        String rawMessage
) {
    public static ResultMessage from(RequestResult result) {
        return new ResultMessage(
                result.requestId(),
                result.targetSystemId(),
                result.outcome().label(),
                result.kind() == null ? null : result.kind().name(),
                result.statusCode(),
                result.resultCode(),
                result.responseBody(),
                result.deadLetterReason() == null ? null : result.deadLetterReason().label(),
                result.expiredWhere() == null ? null : result.expiredWhere().label(),
                result.attempts(),
                result.finishedAt(),
                result.rawMessage());
    }
}
