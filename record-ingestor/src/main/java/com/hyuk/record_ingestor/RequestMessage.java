package com.hyuk.record_ingestor;

import java.time.Instant;

public record RequestMessage(
        String requestId,
        String targetSystemId,
        String payload,
        Instant createdAt
) {
}
