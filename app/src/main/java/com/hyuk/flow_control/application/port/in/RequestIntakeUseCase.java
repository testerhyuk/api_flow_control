package com.hyuk.flow_control.application.port.in;

import com.hyuk.flow_control.domain.request.IntakeResult;
import com.hyuk.flow_control.domain.request.OutboundRequest;

import java.time.Instant;

public interface RequestIntakeUseCase {
    IntakeResult process(OutboundRequest request, Instant deadline);
}
