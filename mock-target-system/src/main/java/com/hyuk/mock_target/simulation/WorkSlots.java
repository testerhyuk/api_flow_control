package com.hyuk.mock_target.simulation;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class WorkSlots {
    private final AdjustableSemaphore permits;
    private final int queueCapacity;
    private final AtomicInteger inUse = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();
    private int capacity;

    public WorkSlots(int capacity, int queueCapacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("동시처리 자리는 0보다 커야 합니다");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("대기열 크기는 0 이상이어야 합니다");
        }
        this.permits = new AdjustableSemaphore(capacity);
        this.queueCapacity = queueCapacity;
        this.capacity = capacity;
    }

    public boolean enter() throws InterruptedException {
        if (permits.tryAcquire(0, TimeUnit.MILLISECONDS)) {
            inUse.incrementAndGet();
            return true;
        }

        if (waiting.incrementAndGet() > queueCapacity) {
            waiting.decrementAndGet();
            return false;
        }

        try {
            permits.acquire();
        } finally {
            waiting.decrementAndGet();
        }

        inUse.incrementAndGet();
        return true;
    }

    public void leave() {
        inUse.decrementAndGet();
        permits.release();
    }

    public synchronized void changeCapacity(int newCapacity) {
        if (newCapacity <= 0) {
            throw new IllegalArgumentException("동시처리 자리는 0보다 커야 합니다");
        }

        int delta = newCapacity - capacity;
        if (delta > 0) {
            permits.release(delta);
        } else if (delta < 0) {
            permits.reduce(-delta);
        }
        capacity = newCapacity;
    }

    public synchronized int capacity() {
        return capacity;
    }

    public int inUse() {
        return inUse.get();
    }

    public int waiting() {
        return waiting.get();
    }

    private static final class AdjustableSemaphore extends Semaphore {
        private AdjustableSemaphore(int permits) {
            super(permits, true);
        }

        private void reduce(int reduction) {
            reducePermits(reduction);
        }
    }
}
