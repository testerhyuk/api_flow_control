package com.hyuk.flow_control.domain.health;

import java.time.Duration;
import java.util.Optional;

public record HealthThresholds(
        int minimumCalls,
        double degradedFailureRate,
        double criticalFailureRate,
        double degradedLatencyMultiplier
) {
    public Optional<HealthGrade> gradeOf(OutcomeStatistics statistics, Duration baselineRtt) {
        if (statistics.sampleCount() < minimumCalls) {
            return Optional.empty();
        }

        double failureRate = statistics.failureRate();

        if (failureRate >= criticalFailureRate) {
            return Optional.of(HealthGrade.CRITICAL);
        }
        if (failureRate >= degradedFailureRate || isSlow(statistics.p95(), baselineRtt)) {
            return Optional.of(HealthGrade.DEGRADED);
        }
        return Optional.of(HealthGrade.GOOD);
    }

    private boolean isSlow(Duration p95, Duration baselineRtt) {
        if (p95 == null) {
            return false;
        }

        double limitNanos = baselineRtt.toNanos() * degradedLatencyMultiplier;

        return p95.toNanos() >= limitNanos;
    }
}
