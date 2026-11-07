package com.hyuk.flow_control.application.port.out;

import java.util.Optional;

/** 요청 읽기를 멈추고 다시 시작하는 곳. 요청 수신이 꺼져 있으면 없고, 서로 기대므로 쓸 때 꺼낸다. */
public interface RequestIntakeControlLookup {
    Optional<RequestIntakeControl> find();
}
