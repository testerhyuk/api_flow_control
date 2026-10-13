package com.hyuk.flow_control.domain.retry;

import com.hyuk.flow_control.domain.request.OutcomeKind;

public enum Disposition {
    COMPLETE,
    DISCARD,
    RETRY,
    DEAD_LETTER;

    public static Disposition of(OutcomeKind kind) {
        return switch (kind) {
            case SUCCESS -> COMPLETE;
            case BUSINESS_REJECTED -> DISCARD;
            case CONNECTION_FAILED, SERVER_ERROR, TOO_MANY_REQUESTS,
                 UNDER_MAINTENANCE, TIMEOUT, NETWORK_ERROR -> RETRY;
            case INVALID_REQUEST, UNEXPECTED_RESPONSE -> DEAD_LETTER;
        };
    }
}
