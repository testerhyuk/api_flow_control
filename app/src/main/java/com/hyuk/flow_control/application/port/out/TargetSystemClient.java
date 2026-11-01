package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;

public interface TargetSystemClient {
    OutboundResult call(OutboundRequest request);

    OutcomeKind probe(String targetSystemId);
}
