package com.hyuk.flow_control.domain.limit;

public enum ControlMode {
    ADAPTIVE("adaptive"),
    FIXED("fixed"),
    NONE("none");

    private final String label;

    ControlMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
