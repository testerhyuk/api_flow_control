package com.hyuk.mock_target.fault;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class Faults {
    private volatile boolean down;
    private volatile Double slowMultiplier;
    private volatile Double failureRate;
    private volatile Long maintenanceRetryAfterSeconds;
    private volatile Duration noResponseHold;
    private volatile Integer capacity;

    public synchronized void apply(FaultType type, Double value) {
        switch (type) {
            case DOWN -> down = true;
            case SLOW -> slowMultiplier = requireAtLeast(value, 1, "느려짐 배수");
            case FAILURE_RATE -> failureRate = requireRate(value);
            case MAINTENANCE -> maintenanceRetryAfterSeconds = (long) requireWhole(value, 0, "점검 Retry-After 초");
            case NO_RESPONSE -> noResponseHold = Duration.ofMillis(Math.round(requirePositive(value, "응답 없음 시간(초)") * 1000));
            case CAPACITY -> capacity = (int) requireWhole(value, 1, "동시처리 자리 수");
        }
    }

    public synchronized void clear(FaultType type) {
        switch (type) {
            case DOWN -> down = false;
            case SLOW -> slowMultiplier = null;
            case FAILURE_RATE -> failureRate = null;
            case MAINTENANCE -> maintenanceRetryAfterSeconds = null;
            case NO_RESPONSE -> noResponseHold = null;
            case CAPACITY -> capacity = null;
        }
    }

    public boolean isActive(FaultType type) {
        return switch (type) {
            case DOWN -> down;
            case SLOW -> slowMultiplier != null;
            case FAILURE_RATE -> failureRate != null;
            case MAINTENANCE -> maintenanceRetryAfterSeconds != null;
            case NO_RESPONSE -> noResponseHold != null;
            case CAPACITY -> capacity != null;
        };
    }

    public synchronized Map<String, Object> describe() {
        Map<String, Object> active = new LinkedHashMap<>();
        Map<FaultType, Object> values = new EnumMap<>(FaultType.class);
        values.put(FaultType.DOWN, down ? Boolean.TRUE : null);
        values.put(FaultType.SLOW, slowMultiplier);
        values.put(FaultType.FAILURE_RATE, failureRate);
        values.put(FaultType.MAINTENANCE, maintenanceRetryAfterSeconds);
        values.put(FaultType.NO_RESPONSE, noResponseHold == null ? null : noResponseHold.toMillis() / 1000.0);
        values.put(FaultType.CAPACITY, capacity);
        values.forEach((type, value) -> {
            if (value != null) {
                active.put(type.id(), value);
            }
        });
        return active;
    }

    public double slowMultiplier() {
        Double value = slowMultiplier;
        return value == null ? 1.0 : value;
    }

    public Double failureRate() {
        return failureRate;
    }

    public Long maintenanceRetryAfterSeconds() {
        return maintenanceRetryAfterSeconds;
    }

    public Duration noResponseHold() {
        return noResponseHold;
    }

    public Integer capacity() {
        return capacity;
    }

    private static double requireAtLeast(Double value, double min, String name) {
        if (value == null || value < min) {
            throw new IllegalArgumentException(name + " 는 " + min + " 이상이어야 합니다");
        }
        return value;
    }

    private static double requireWhole(Double value, double min, String name) {
        double checked = requireAtLeast(value, min, name);
        if (checked != Math.rint(checked)) {
            throw new IllegalArgumentException(name + " 는 정수여야 합니다");
        }
        return checked;
    }

    private static double requirePositive(Double value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " 는 0보다 커야 합니다");
        }
        return value;
    }

    private static double requireRate(Double value) {
        if (value == null || value < 0 || value > 1) {
            throw new IllegalArgumentException("실패율은 0 이상 1 이하여야 합니다");
        }
        return value;
    }
}
