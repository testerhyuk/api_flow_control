package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.requester.Requester;

public interface RequesterChangeListener {
    void onRequesterChanged(Requester before, Requester after);
}
