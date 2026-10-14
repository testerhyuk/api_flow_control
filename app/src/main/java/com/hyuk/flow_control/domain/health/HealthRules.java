package com.hyuk.flow_control.domain.health;

import java.time.Duration;
import java.util.Optional;

public record HealthRules(
        HealthThresholds thresholds,
        int requiredGoodCount,
        int requiredDegradedStreak,
        boolean antiOscillation
) {
    public HealthRules {
        if (thresholds == null) {
            throw new IllegalArgumentException("thresholds 는 필수입니다");
        }
        if (requiredGoodCount < 1 || requiredDegradedStreak < 1) {
            throw new IllegalArgumentException("연속 횟수는 1 이상이어야 합니다");
        }
    }

    public Judgment judge(HealthStatus current, OutcomeStatistics statistics, Duration baselineRtt) {
        HealthThresholds plain = thresholds.withoutHysteresis();
        HealthThresholds active = antiOscillation ? thresholds : plain;
        int degradedStreak = antiOscillation ? requiredDegradedStreak : 1;

        Optional<HealthGrade> grade = active.gradeOf(statistics, baselineRtt, current.state());
        if (grade.isEmpty()) {
            return new Judgment(current, Optional.empty(), Optional.empty());
        }

        HealthStatus next = current.next(grade.get(), requiredGoodCount, degradedStreak);

        if (!antiOscillation) {
            return new Judgment(next, grade, Optional.empty());
        }

        HealthGrade plainGrade = plain.gradeOf(statistics, baselineRtt, current.state()).orElseThrow();
        HealthStatus plainNext = current.next(plainGrade, requiredGoodCount, 1);

        if (plainNext.state() == next.state()) {
            return new Judgment(next, grade, Optional.empty());
        }

        SuppressedBy reason = plainGrade != grade.get() ? SuppressedBy.HYSTERESIS : SuppressedBy.CONSECUTIVE;
        return new Judgment(next, grade, Optional.of(reason));
    }

    public HealthThresholds activeThresholds() {
        return antiOscillation ? thresholds : thresholds.withoutHysteresis();
    }

    public record Judgment(HealthStatus next, Optional<HealthGrade> grade, Optional<SuppressedBy> suppressedBy) {
    }
}
