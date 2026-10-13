package com.hyuk.flow_control.application.port.in;

import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.retry.DeliveryContext;
import com.hyuk.flow_control.domain.retry.HandlingOutcome;

public interface RequestHandlingUseCase {
    HandlingOutcome handle(OutboundRequest request, DeliveryContext context);

    HandlingOutcome rejectInvalid(String targetSystemId, String rawValue, DeliveryContext context, String reason);
}
