package com.hyuk.mock_target.simulation;

import java.time.Duration;

public final class ProcessingTime {
    private static final double Z_95 = 1.6448536269514722;

    private ProcessingTime() {
    }

    public static Duration of(long baseMillis, double loadFactor, double jitter, double faultMultiplier) {
        double millis = baseMillis * loadFactor * jitter * faultMultiplier;
        return Duration.ofNanos(Math.round(millis * 1_000_000));
    }

    public static double loadFactor(int inUse, int capacity, double fullLoadMultiplier) {
        double ratio = Math.min(1.0, Math.max(0.0, (double) inUse / capacity));
        return 1 + (fullLoadMultiplier - 1) * ratio * ratio;
    }

    public static double jitter(double gaussian, double p95Ratio) {
        double sigma = Math.log(p95Ratio) / Z_95;
        return Math.exp(sigma * gaussian);
    }
}
