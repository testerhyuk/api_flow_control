package com.hyuk.mock_target.fault;

import java.util.Arrays;

public enum FaultType {
    DOWN("down"),
    SLOW("slow"),
    FAILURE_RATE("failure-rate"),
    MAINTENANCE("maintenance"),
    NO_RESPONSE("no-response"),
    CAPACITY("capacity");

    private final String id;

    FaultType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static FaultType fromId(String id) {
        return Arrays.stream(values())
                .filter(type -> type.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("모르는 장애 종류입니다 : " + id));
    }
}
