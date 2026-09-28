package com.hyuk.flow_control.domain.request;

import java.time.Instant;

public record OutboundRequest(
        String requestId,
        String targetSystemId,
        String payload,
        Instant createdAt
) {
}
