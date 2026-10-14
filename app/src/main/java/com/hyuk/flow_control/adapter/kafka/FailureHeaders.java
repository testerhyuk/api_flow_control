package com.hyuk.flow_control.adapter.kafka;

import com.hyuk.flow_control.domain.expiry.ExpiryPoint;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.retry.DeadLetterReason;
import com.hyuk.flow_control.domain.retry.DeliveryContext;
import com.hyuk.flow_control.domain.retry.RetryStage;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

public final class FailureHeaders {
    public static final String FAILED_ATTEMPTS = "flow-control-failed-attempts";
    public static final String FIRST_FAILED_AT = "flow-control-first-failed-at";
    public static final String LAST_FAILED_AT = "flow-control-last-failed-at";
    public static final String LAST_KIND = "flow-control-last-kind";
    public static final String ORIGIN = "flow-control-origin";
    public static final String RETRY_STAGE = "flow-control-retry-stage";
    public static final String DEAD_LETTER_REASON = "flow-control-dlq-reason";
    public static final String ERROR = "flow-control-error";
    public static final String STATUS_CODE = "flow-control-status-code";
    public static final String RESULT_CODE = "flow-control-result-code";
    public static final String EXPIRY_REASON = "flow-control-expiry-reason";
    public static final String EXPIRED_WHERE = "flow-control-expired-where";
    public static final String EXPIRED_AGE_MS = "flow-control-expired-age-ms";

    public static final String TTL_EXCEEDED = "ttl_exceeded";

    private FailureHeaders() {
    }

    public static void writeContext(Headers headers, DeliveryContext context, OutcomeKind lastKind) {
        put(headers, FAILED_ATTEMPTS, String.valueOf(context.failedAttempts()));
        put(headers, FIRST_FAILED_AT, context.firstFailedAt());
        put(headers, LAST_FAILED_AT, context.lastFailedAt());
        put(headers, LAST_KIND, lastKind == null ? null : lastKind.name());
        put(headers, ORIGIN, context.origin());
        put(headers, RETRY_STAGE, context.stage() == null ? null : context.stage().label());
    }

    public static void writeDeadLetter(Headers headers, DeadLetterReason reason, String error) {
        put(headers, DEAD_LETTER_REASON, reason.label());
        put(headers, ERROR, error);
    }

    public static void writeExpiry(Headers headers, ExpiryPoint where, Duration age) {
        put(headers, EXPIRY_REASON, TTL_EXCEEDED);
        put(headers, EXPIRED_WHERE, where.label());
        put(headers, EXPIRED_AGE_MS, age.toMillis());
    }

    public static void writeResponseCodes(Headers headers, Integer statusCode, String resultCode) {
        put(headers, STATUS_CODE, statusCode);
        put(headers, RESULT_CODE, resultCode);
    }

    public static DeliveryContext readContext(Headers headers, RetryStage stage, String fallbackOrigin) {
        String origin = read(headers, ORIGIN);
        String attempts = read(headers, FAILED_ATTEMPTS);

        return new DeliveryContext(
                stage,
                attempts == null ? 0 : Integer.parseInt(attempts),
                instant(read(headers, FIRST_FAILED_AT)),
                instant(read(headers, LAST_FAILED_AT)),
                origin == null ? fallbackOrigin : origin,
                kind(read(headers, LAST_KIND)));
    }

    public static String read(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static void put(Headers headers, String key, Object value) {
        headers.remove(key);
        if (value != null) {
            headers.add(key, value.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static OutcomeKind kind(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OutcomeKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
