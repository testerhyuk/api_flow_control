package com.hyuk.record_ingestor;

import java.util.SplittableRandom;

public final class ArrivalSchedule {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final SplittableRandom random;

    public ArrivalSchedule(long seed, String targetSystemId) {
        this.random = new SplittableRandom(seed * 31 + targetSystemId.hashCode());
    }

    public synchronized long nextDelayNanos(double rate) {
        if (rate <= 0) {
            throw new IllegalArgumentException("rate 는 0보다 커야 합니다");
        }
        double uniform = random.nextDouble();
        return Math.max(1, Math.round(-Math.log(1 - uniform) / rate * NANOS_PER_SECOND));
    }
}
