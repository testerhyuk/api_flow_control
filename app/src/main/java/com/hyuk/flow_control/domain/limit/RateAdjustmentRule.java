package com.hyuk.flow_control.domain.limit;

import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;

public record RateAdjustmentRule(
        double degradedRatio,
        double recoveryStep,
        int minimumCalls,
        double criticalRate
) {
    public double next(double current, HealthState state, int sampleCount, TargetSystemPolicy policy) {
        double effectiveRate = policy.effectiveRate();
        double floor = floor(policy);

        double next = switch (state) {
            case CRITICAL -> floor;
            case DEGRADED -> Math.min(current, effectiveRate * degradedRatio);
            case RECOVERING, HEALTHY -> sampleCount >= minimumCalls
                    ? Math.min(effectiveRate, current + effectiveRate * recoveryStep)
                    : current;
        };

        return Math.min(effectiveRate, Math.max(floor, next));
    }

    public double floor(TargetSystemPolicy policy) {
        return Math.min(criticalRate, policy.effectiveRate());
    }

    public double bounded(double rate, TargetSystemPolicy policy) {
        return Math.min(policy.effectiveRate(), Math.max(floor(policy), rate));
    }
}
