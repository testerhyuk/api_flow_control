package com.hyuk.flow_control.domain.retry;

public enum DeadLetterReason {
    DLQ_KIND("dlq_kind"),
    RETRY_EXHAUSTED("retry_exhausted"),
    INVALID_MESSAGE("invalid_message"),
    PROCESSING_ERROR("processing_error");

    private final String label;

    DeadLetterReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
