package com.hyuk.flow_control.domain.dryrun;

public class CountingTokenBucket {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private double ratePerSecond;
    private double capacity;
    private double tokens;
    private long lastRefillNanos;

    public CountingTokenBucket(double ratePerSecond, int capacity, long nowNanos) {
        validate(ratePerSecond, capacity);
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    public synchronized boolean tryConsume(long nowNanos) {
        refill(nowNanos);

        if (tokens < 1) {
            return false;
        }

        tokens -= 1;
        return true;
    }

    public synchronized void change(double newRatePerSecond, int newCapacity, long nowNanos) {
        validate(newRatePerSecond, newCapacity);
        refill(nowNanos);
        this.ratePerSecond = newRatePerSecond;
        this.capacity = newCapacity;
        this.tokens = Math.min(tokens, newCapacity);
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;

        if (elapsed <= 0) {
            return;
        }

        tokens = Math.min(capacity, tokens + elapsed / NANOS_PER_SECOND * ratePerSecond);
        lastRefillNanos = nowNanos;
    }

    private static void validate(double ratePerSecond, int capacity) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("rate 는 0보다 커야 합니다");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("버킷 크기는 0보다 커야 합니다");
        }
    }
}
