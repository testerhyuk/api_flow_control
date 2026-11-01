package com.hyuk.flow_control.domain.result;

public enum FinalOutcome {
    COMPLETED("completed"),
    DISCARDED("discarded"),
    DEAD_LETTERED("dead_lettered"),
    EXPIRED("expired");

    private final String label;

    FinalOutcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
