package com.hyuk.flow_control.adapter.kafka;

import com.hyuk.flow_control.domain.request.OutboundRequest;

import java.time.Instant;

public record RequestMessage(
        String requestId,
        String targetSystemId,
        String payload,
        Instant createdAt
) {
    public static RequestMessage from(OutboundRequest request) {
        return new RequestMessage(request.requestId(), request.targetSystemId(), request.payload(), request.createdAt());
    }

    public boolean isValid() {
        return requestId != null && !requestId.isBlank()
                && targetSystemId != null && !targetSystemId.isBlank()
                && payload != null && !payload.isBlank()
                && createdAt != null;
    }

    public OutboundRequest toOutboundRequest() {
        return new OutboundRequest(requestId, targetSystemId, payload, createdAt);
    }
}
