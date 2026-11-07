package com.hyuk.flow_control.domain.health;

import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;

import java.time.Duration;

/** 판정에 쓰는 평소 응답 시간 (평균, p95). */
public record Baselines(Duration mean, Duration p95) {
    public Baselines {
        if (mean == null || !mean.isPositive() || p95 == null || !p95.isPositive()) {
            throw new IllegalArgumentException("평소 응답 시간은 0보다 커야 합니다");
        }
    }

    public static Baselines of(TargetSystemPolicy limits) {
        return new Baselines(limits.baselineRtt(), limits.baselineP95());
    }
}
