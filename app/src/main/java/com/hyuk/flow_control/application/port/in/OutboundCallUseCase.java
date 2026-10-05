package com.hyuk.flow_control.application.port.in;

import com.hyuk.flow_control.domain.request.CallAttempt;
import com.hyuk.flow_control.domain.request.OutboundRequest;

public interface OutboundCallUseCase {
    CallAttempt send(OutboundRequest request);
}
