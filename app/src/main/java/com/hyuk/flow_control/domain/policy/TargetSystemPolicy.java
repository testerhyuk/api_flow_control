package com.hyuk.flow_control.domain.policy;

import java.time.Duration;

public record TargetSystemPolicy(
        int maxTps,
        Duration baselineRtt,
        double safetyMargin,
        Duration burstWindow
) {
    public TargetSystemPolicy {
        if (maxTps <= 0) {
            throw new IllegalArgumentException("max-tps는 0보다 커야 합니다");
        }
        if (baselineRtt == null || !baselineRtt.isPositive()) {
            throw new IllegalArgumentException("baseline-rtt는 0보다 커야 합니다");
        }
        if (safetyMargin <= 0 || safetyMargin > 1) {
            throw new IllegalArgumentException("safety-margin은 0보다 크고 1 이하여야 합니다");
        }
        if (burstWindow == null || !burstWindow.isPositive()) {
            throw new IllegalArgumentException("burst-window는 0보다 커야 합니다");
        }
    }

    public double effectiveRate() {
        return maxTps * safetyMargin;
    }

    public int concurrencyLimit() {
        return concurrencyLimitAt(effectiveRate());
    }

    public int burstCapacity() {
        return burstCapacityAt(effectiveRate());
    }

    public Duration refillInterval() {
        return refillIntervalAt(effectiveRate());
    }

    public int concurrencyLimitAt(double rate) {
        requirePositive(rate);
        return atLeastOne(rate * toSeconds(baselineRtt));
    }

    public int burstCapacityAt(double rate) {
        requirePositive(rate);
        return atLeastOne(rate * toSeconds(burstWindow));
    }

    public Duration refillIntervalAt(double rate) {
        requirePositive(rate);
        return Duration.ofNanos(Math.round(1_000_000_000.0 / rate));
    }

    private static void requirePositive(double rate) {
        if (rate <= 0) {
            throw new IllegalArgumentException("rate는 0보다 커야 합니다");
        }
    }

    private static double toSeconds(Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }

    private static int atLeastOne(double value) {
        return Math.max(1, (int) Math.ceil(value - 1e-9));
    }
}
