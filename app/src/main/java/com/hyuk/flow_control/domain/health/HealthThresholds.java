package com.hyuk.flow_control.domain.health;

import java.time.Duration;
import java.util.Optional;

public record HealthThresholds(
        int minimumCalls,
        double degradedFailureRate,
        double criticalFailureRate,
        double degradedLatencyMultiplier,
        double degradedRecoveryFailureRate,
        double criticalRecoveryFailureRate,
        double recoveryLatencyMultiplier
) {
    public HealthThresholds {
        if (degradedRecoveryFailureRate > degradedFailureRate) {
            throw new IllegalArgumentException("DEGRADED 복귀 실패율은 진입 실패율보다 클 수 없습니다");
        }
        if (criticalRecoveryFailureRate > criticalFailureRate) {
            throw new IllegalArgumentException("CRITICAL 복귀 실패율은 진입 실패율보다 클 수 없습니다");
        }
        if (recoveryLatencyMultiplier > degradedLatencyMultiplier) {
            throw new IllegalArgumentException("latency 복귀 배수는 진입 배수보다 클 수 없습니다");
        }
    }

    public HealthThresholds(int minimumCalls, double degradedFailureRate, double criticalFailureRate,
                            double degradedLatencyMultiplier) {
        this(minimumCalls, degradedFailureRate, criticalFailureRate, degradedLatencyMultiplier,
                degradedFailureRate, criticalFailureRate, degradedLatencyMultiplier);
    }

    public HealthThresholds withoutHysteresis() {
        return new HealthThresholds(minimumCalls, degradedFailureRate, criticalFailureRate, degradedLatencyMultiplier);
    }

    public Optional<HealthGrade> gradeOf(OutcomeStatistics statistics, Duration baselineRtt) {
        return gradeOf(statistics, baselineRtt, HealthState.HEALTHY);
    }

    public Optional<HealthGrade> gradeOf(OutcomeStatistics statistics, Duration baselineRtt, HealthState current) {
        if (statistics.sampleCount() < minimumCalls) {
            return Optional.empty();
        }

        double failureRate = statistics.failureRate();
        double criticalLine = current == HealthState.CRITICAL ? criticalRecoveryFailureRate : criticalFailureRate;

        if (failureRate >= criticalLine) {
            return Optional.of(HealthGrade.CRITICAL);
        }
        if (failureRate >= degradedFailureRate || atLeast(statistics.p95(), baselineRtt, degradedLatencyMultiplier)) {
            return Optional.of(HealthGrade.DEGRADED);
        }
        if (failureRate < degradedRecoveryFailureRate && !atLeast(statistics.p95(), baselineRtt, recoveryLatencyMultiplier)) {
            return Optional.of(HealthGrade.GOOD);
        }
        return Optional.of(HealthGrade.HOLD);
    }

    public Duration latencyThreshold(Duration baselineRtt) {
        return multiply(baselineRtt, degradedLatencyMultiplier);
    }

    public Duration recoveryLatencyThreshold(Duration baselineRtt) {
        return multiply(baselineRtt, recoveryLatencyMultiplier);
    }

    private static boolean atLeast(Duration p95, Duration baselineRtt, double multiplier) {
        if (p95 == null) {
            return false;
        }
        return p95.toNanos() >= baselineRtt.toNanos() * multiplier;
    }

    private static Duration multiply(Duration duration, double multiplier) {
        return Duration.ofNanos(Math.round(duration.toNanos() * multiplier));
    }
}
