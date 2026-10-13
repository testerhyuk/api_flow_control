package com.hyuk.flow_control.domain.retry;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

public enum RetryStage {
    FIRST("1s", Duration.ofSeconds(1)),
    SECOND("10s", Duration.ofSeconds(10)),
    THIRD("60s", Duration.ofSeconds(60)),
    FOURTH("300s", Duration.ofSeconds(300));

    private final String label;
    private final Duration delay;

    RetryStage(String label, Duration delay) {
        this.label = label;
        this.delay = delay;
    }

    public String label() {
        return label;
    }

    public Duration delay() {
        return delay;
    }

    public static Optional<RetryStage> firstAfter(RetryStage current, Duration minimumDelay) {
        int start = current == null ? 0 : current.ordinal() + 1;

        return Arrays.stream(values())
                .skip(start)
                .filter(stage -> stage.delay.compareTo(minimumDelay) >= 0)
                .findFirst();
    }
}
