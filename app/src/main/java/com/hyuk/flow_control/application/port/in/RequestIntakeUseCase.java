package com.hyuk.flow_control.application.port.in;

import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;

public interface RequestIntakeUseCase {
    OutboundResult process(OutboundRequest request);
}
