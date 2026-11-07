package com.hyuk.flow_control.application.port.out;

import java.util.List;

/** 요청자가 바뀌면 알릴 곳들. 받는 곳들이 요청자를 읽으려고 RequesterRegistry 에 기대므로, 목록은 알릴 때 꺼낸다. */
public interface RequesterChangeListeners {
    /** 알릴 순서대로. */
    List<RequesterChangeListener> all();
}
