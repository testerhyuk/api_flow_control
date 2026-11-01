package com.hyuk.flow_control.domain.health;

public record HealthStatus(
        HealthState state,
        int consecutiveGoodCount,
        int degradedStreak,
        int uncountedSamples
) {
    public HealthStatus {
        if (state == null) {
            throw new IllegalArgumentException("state는 필수입니다");
        }
        if (consecutiveGoodCount < 0) {
            throw new IllegalArgumentException("consecutiveGoodCount는 0 이상이어야 합니다");
        }
        if (degradedStreak < 0) {
            throw new IllegalArgumentException("degradedStreak는 0 이상이어야 합니다");
        }
        if (uncountedSamples < 0) {
            throw new IllegalArgumentException("uncountedSamples는 0 이상이어야 합니다");
        }
    }

    public HealthStatus(HealthState state, int consecutiveGoodCount, int degradedStreak) {
        this(state, consecutiveGoodCount, degradedStreak, 0);
    }

    public HealthStatus(HealthState state, int consecutiveGoodCount) {
        this(state, consecutiveGoodCount, 0);
    }

    public static HealthStatus initial() {
        return of(HealthState.HEALTHY);
    }

    public static HealthStatus of(HealthState state) {
        return new HealthStatus(state, 0, 0);
    }

    public HealthStatus next(HealthGrade grade, int requiredGoodCount) {
        return next(grade, requiredGoodCount, 1);
    }

    public HealthStatus next(HealthGrade grade, int requiredGoodCount, int requiredDegradedStreak) {
        return next(grade, requiredGoodCount, requiredDegradedStreak, 1, 1);
    }

    public HealthStatus next(HealthGrade grade, int requiredGoodCount, int requiredDegradedStreak,
                             int newSamples, int batchSize) {
        boolean freshBatch = uncountedSamples + newSamples >= batchSize;

        return switch (state) {
            case HEALTHY -> afterHealthy(grade, requiredDegradedStreak, freshBatch, newSamples);
            case DEGRADED -> afterTrouble(grade, HealthState.DEGRADED);
            case CRITICAL -> afterTrouble(grade, HealthState.CRITICAL);
            case RECOVERING -> afterRecovering(grade, requiredGoodCount, requiredDegradedStreak, freshBatch,
                    newSamples);
        };
    }

    public HealthStatus withNewSamples(int newSamples) {
        if (state != HealthState.RECOVERING && degradedStreak == 0) {
            return this;
        }
        return new HealthStatus(state, consecutiveGoodCount, degradedStreak, uncountedSamples + newSamples);
    }

    private HealthStatus afterHealthy(HealthGrade grade, int requiredDegradedStreak, boolean freshBatch,
                                      int newSamples) {
        if (grade != HealthGrade.CRITICAL && degradedStreak > 0 && !freshBatch) {
            return withNewSamples(newSamples);
        }

        return switch (grade) {
            case GOOD, HOLD -> of(HealthState.HEALTHY);
            case DEGRADED -> degradedOrStay(HealthState.HEALTHY, 0, requiredDegradedStreak);
            case CRITICAL -> of(HealthState.CRITICAL);
        };
    }

    private HealthStatus afterTrouble(HealthGrade grade, HealthState current) {
        return switch (grade) {
            case GOOD -> new HealthStatus(HealthState.RECOVERING, 1, 0);
            case HOLD -> of(HealthState.DEGRADED);
            case DEGRADED -> of(HealthState.DEGRADED);
            case CRITICAL -> of(HealthState.CRITICAL);
        };
    }

    private HealthStatus afterRecovering(HealthGrade grade, int requiredGoodCount, int requiredDegradedStreak,
                                         boolean freshBatch, int newSamples) {
        boolean firstDegraded = grade == HealthGrade.DEGRADED && degradedStreak == 0;
        if (grade != HealthGrade.CRITICAL && !firstDegraded && !freshBatch) {
            return withNewSamples(newSamples);
        }

        return switch (grade) {
            case GOOD -> {
                int goodCount = consecutiveGoodCount + 1;
                yield goodCount >= requiredGoodCount
                        ? of(HealthState.HEALTHY)
                        : new HealthStatus(HealthState.RECOVERING, goodCount, 0);
            }
            case HOLD -> of(HealthState.RECOVERING);
            case DEGRADED -> degradedOrStay(HealthState.RECOVERING, 0, requiredDegradedStreak);
            case CRITICAL -> of(HealthState.CRITICAL);
        };
    }

    private HealthStatus degradedOrStay(HealthState stay, int goodCount, int requiredDegradedStreak) {
        int streak = degradedStreak + 1;

        if (streak >= requiredDegradedStreak) {
            return of(HealthState.DEGRADED);
        }
        return new HealthStatus(stay, goodCount, streak);
    }
}
