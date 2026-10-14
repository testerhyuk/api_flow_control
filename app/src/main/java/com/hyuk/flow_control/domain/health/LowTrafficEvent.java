package com.hyuk.flow_control.domain.health;

public enum LowTrafficEvent {
    TOO_MANY_REQUESTS,
    CONSECUTIVE_FAILURES,
    CONSECUTIVE_TIMEOUTS
}
