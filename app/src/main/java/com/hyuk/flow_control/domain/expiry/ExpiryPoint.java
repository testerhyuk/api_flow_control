package com.hyuk.flow_control.domain.expiry;

public enum ExpiryPoint {
    INTAKE("intake"),
    RETRY_SCHEDULE("retry_schedule");

    private final String label;

    ExpiryPoint(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
