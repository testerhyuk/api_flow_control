package com.hyuk.flow_control.domain.health;

import com.hyuk.flow_control.domain.request.OutcomeKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public record OutcomeStatistics(
        int sampleCount,
        int badCount,
        Duration p95
) {
    public static OutcomeStatistics from(List<RecordedOutcome> outcomes) {
        int sampleCount = 0;
        int badCount = 0;
        List<Duration> latencies = new ArrayList<>();

        for (RecordedOutcome outcome : outcomes) {
            switch (categoryOf(outcome.kind())) {
                case NOT_BAD -> {
                    sampleCount++;
                    latencies.add(outcome.latency());
                }
                case BAD -> {
                    sampleCount++;
                    badCount++;
                }
                case EXCLUDED -> {
                }
            }
        }

        return new OutcomeStatistics(sampleCount, badCount, percentile95(latencies));
    }

    public double failureRate() {
        if (sampleCount == 0) {
            return 0.0;
        }
        return (double) badCount / sampleCount;
    }

    public static boolean isSample(OutcomeKind kind) {
        return categoryOf(kind) != Category.EXCLUDED;
    }

    public static boolean isFailure(OutcomeKind kind) {
        return categoryOf(kind) == Category.BAD;
    }

    private enum Category { NOT_BAD, BAD, EXCLUDED }

    private static Category categoryOf(OutcomeKind kind) {
        return switch (kind) {
            case SUCCESS, BUSINESS_REJECTED, INVALID_REQUEST -> Category.NOT_BAD;
            case TIMEOUT, CONNECTION_FAILED, NETWORK_ERROR, SERVER_ERROR,
                 TOO_MANY_REQUESTS, UNDER_MAINTENANCE -> Category.BAD;
            case UNEXPECTED_RESPONSE -> Category.EXCLUDED;
        };
    }

    private static Duration percentile95(List<Duration> latencies) {
        if (latencies.isEmpty()) {
            return null;
        }

        List<Duration> sorted = latencies.stream().sorted().toList();
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;

        return sorted.get(index);
    }
}
