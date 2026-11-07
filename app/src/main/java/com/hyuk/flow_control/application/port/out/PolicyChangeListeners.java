package com.hyuk.flow_control.application.port.out;

import java.util.List;

/**
 * 정책이 바뀌면 알릴 곳들. 받는 곳들이 정책을 읽으려고 PolicyRegistry 에 기대므로,
 * 목록은 만들 때가 아니라 알릴 때 꺼낸다.
 */
public interface PolicyChangeListeners {
    /** 알릴 순서대로. */
    List<PolicyChangeListener> all();

    /** 지표와 로그에 남길 받는 곳 이름. */
    default String nameOf(PolicyChangeListener listener) {
        return listener.getClass().getSimpleName();
    }
}
