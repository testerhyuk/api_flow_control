package com.hyuk.flow_control.domain.health;

public record HealthStatus(
        HealthState state,
        int consecutiveGoodCount
) {
    public HealthStatus {
        if (state == null) {
            throw new IllegalArgumentException("state는 필수입니다");
        }
        if (consecutiveGoodCount < 0) {
            throw new IllegalArgumentException("consecutiveGoodCount는 0 이상이어야 합니다");
        }
    }

    public static HealthStatus initial() {
        return new HealthStatus(HealthState.HEALTHY, 0);
    }

    public HealthStatus next(HealthGrade grade, int requiredGoodCount) {
        return switch (state) {
            case HEALTHY -> afterHealthy(grade);
            case DEGRADED, CRITICAL -> afterTrouble(grade);
            case RECOVERING -> afterRecovering(grade, requiredGoodCount);
        };
    }

    private HealthStatus afterHealthy(HealthGrade grade) {
        return switch (grade) {
            case GOOD -> new HealthStatus(HealthState.HEALTHY, 0);
            case DEGRADED -> new HealthStatus(HealthState.DEGRADED, 0);
            case CRITICAL -> new HealthStatus(HealthState.CRITICAL, 0);
        };
    }

    private HealthStatus afterTrouble(HealthGrade grade) {
        return switch (grade) {
            case GOOD -> new HealthStatus(HealthState.RECOVERING, 1);
            case DEGRADED -> new HealthStatus(HealthState.DEGRADED, 0);
            case CRITICAL -> new HealthStatus(HealthState.CRITICAL, 0);
        };
    }

    private HealthStatus afterRecovering(HealthGrade grade, int requiredGoodCount) {
        if (grade != HealthGrade.GOOD) {
            return new HealthStatus(HealthState.CRITICAL, 0);
        }

        int goodCount = consecutiveGoodCount + 1;

        if (goodCount >= requiredGoodCount) {
            return new HealthStatus(HealthState.HEALTHY, 0);
        }
        return new HealthStatus(HealthState.RECOVERING, goodCount);
    }
}
