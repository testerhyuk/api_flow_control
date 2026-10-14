package com.hyuk.mock_target.simulation;

import java.util.function.LongSupplier;

public class PerSecondLimiter {
    private final int maxPerSecond;
    private final LongSupplier currentMillis;

    private long windowSecond = Long.MIN_VALUE;
    private int count;

    public PerSecondLimiter(int maxPerSecond, LongSupplier currentMillis) {
        if (maxPerSecond <= 0) {
            throw new IllegalArgumentException("초당 한도는 0보다 커야 합니다");
        }
        this.maxPerSecond = maxPerSecond;
        this.currentMillis = currentMillis;
    }

    public synchronized boolean tryAcquire() {
        long second = Math.floorDiv(currentMillis.getAsLong(), 1000L);

        if (second != windowSecond) {
            windowSecond = second;
            count = 0;
        }

        if (count >= maxPerSecond) {
            return false;
        }

        count++;
        return true;
    }
}
