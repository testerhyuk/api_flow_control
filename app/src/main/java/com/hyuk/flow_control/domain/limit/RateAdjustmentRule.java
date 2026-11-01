package com.hyuk.flow_control.domain.limit;

import com.hyuk.flow_control.domain.health.HealthGrade;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;

import java.util.Optional;

public record RateAdjustmentRule(
        double degradedRatio,
        int minimumCalls,
        double criticalRate
) {
    public double next(double current, HealthState state, Optional<HealthGrade> grade, int samplesSinceChange,
                       TargetSystemPolicy policy) {
        double effectiveRate = policy.effectiveRate();
        double floor = floor(policy);
        boolean raise = canRaise(state, grade, samplesSinceChange);

        double next = switch (state) {
            case CRITICAL -> floor;
            case DEGRADED -> raised(current, raise, policy, effectiveRate * degradedRatio);
            case RECOVERING, HEALTHY -> raised(current, raise, policy, effectiveRate);
        };

        return Math.min(effectiveRate, Math.max(floor, next));
    }

    private boolean canRaise(HealthState state, Optional<HealthGrade> grade, int samplesSinceChange) {
        if (samplesSinceChange < minimumCalls || grade.isEmpty()) {
            return false;
        }

        return switch (state) {
            case CRITICAL -> false;
            case DEGRADED -> true;
            case RECOVERING -> grade.get() == HealthGrade.GOOD;
            case HEALTHY -> grade.get() == HealthGrade.GOOD || grade.get() == HealthGrade.HOLD;
        };
    }

    private double raised(double current, boolean raise, TargetSystemPolicy policy, double ceiling) {
        if (current >= ceiling) {
            return ceiling;
        }
        if (!raise) {
            return current;
        }

        double safetyMargin = policy.safetyMargin();
        return Math.min(ceiling, current + current * (1 - safetyMargin) / safetyMargin);
    }

    public double floor(TargetSystemPolicy policy) {
        return Math.min(criticalRate, policy.effectiveRate());
    }

    public double bounded(double rate, TargetSystemPolicy policy) {
        return Math.min(policy.effectiveRate(), Math.max(floor(policy), rate));
    }
}
