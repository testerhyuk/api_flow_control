package com.hyuk.mock_target.simulation;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class HeldRequests {
    private final AtomicInteger count = new AtomicInteger();
    private final Sleeper sleeper;

    public HeldRequests(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    public void hold(Duration duration) throws InterruptedException {
        count.incrementAndGet();
        try {
            sleeper.sleep(duration);
        } finally {
            count.decrementAndGet();
        }
    }

    public int count() {
        return count.get();
    }
}
