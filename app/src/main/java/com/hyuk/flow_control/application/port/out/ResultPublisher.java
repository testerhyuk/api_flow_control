package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.result.RequestResult;

public interface ResultPublisher {
    void publish(String requesterId, RequestResult result);
}
