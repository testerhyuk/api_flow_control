package com.hyuk.flow_control.domain.request;

import java.time.Duration;
import java.time.Instant;

public record OutboundResult(
        OutcomeKind kind,
        Duration latency,
        Instant retryAfter,
        String responseBody
) {
}
