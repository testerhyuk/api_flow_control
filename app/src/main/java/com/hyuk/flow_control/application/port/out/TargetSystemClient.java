package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;

public interface TargetSystemClient {
    OutboundResult call(OutboundRequest request);
}
