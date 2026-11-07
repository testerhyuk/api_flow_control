package com.hyuk.flow_control.application.port.in;

import java.util.List;

/** 외부 서버를 부를 수 있는 요청자를 읽는다. */
public interface RequesterQuery {
    List<String> requesterIdsOf(String targetSystemId);

    List<String> enabledRequesterIdsOf(String targetSystemId);
}
