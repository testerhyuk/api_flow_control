package com.hyuk.integration_test.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

public final class Await {
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private Await() {
    }

    public static void until(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException lastError = null;

        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
                lastError = null;
            } catch (RuntimeException e) {
                lastError = e;
            }
            sleep(POLL_INTERVAL);
        }

        throw new AssertionError(timeout.toSeconds() + "초 동안 기다렸지만 되지 않았습니다 : " + description, lastError);
    }

    public static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("기다리는 중 중단되었습니다", e);
        }
    }
}
