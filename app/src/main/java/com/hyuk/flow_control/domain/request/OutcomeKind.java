package com.hyuk.flow_control.domain.request;

public enum OutcomeKind {
    SUCCESS,
    BUSINESS_REJECTED,
    TIMEOUT,
    SERVER_ERROR,
    TOO_MANY_REQUESTS,
    UNDER_MAINTENANCE,
    INVALID_REQUEST
}
