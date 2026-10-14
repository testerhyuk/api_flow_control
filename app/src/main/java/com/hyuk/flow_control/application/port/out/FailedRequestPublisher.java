package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.expiry.ExpiryPoint;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.retry.DeadLetterReason;
import com.hyuk.flow_control.domain.retry.DeliveryContext;

import java.time.Duration;

public interface FailedRequestPublisher {
    void scheduleRetry(OutboundRequest request, DeliveryContext context, OutcomeKind lastKind);

    void deadLetter(OutboundRequest request, DeliveryContext context, DeadLetterReason reason,
                    OutboundResult lastResult, String error);

    void deadLetterRaw(String targetSystemId, String rawValue, DeliveryContext context, String error);

    void expire(OutboundRequest request, DeliveryContext context, ExpiryPoint where, OutcomeKind lastKind, Duration age);
}
