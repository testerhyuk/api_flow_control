package com.hyuk.flow_control.domain.maintenance;

public enum MaintenanceSource {
    SCHEDULE("schedule"),
    RESPONSE("response");

    private final String label;

    MaintenanceSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
