package com.hyuk.mock_target.simulation;

import java.time.Duration;

@FunctionalInterface
public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;

    static Sleeper threadSleep() {
        return duration -> {
            if (duration.isPositive()) {
                Thread.sleep(duration);
            }
        };
    }
}
