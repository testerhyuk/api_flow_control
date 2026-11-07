package com.hyuk.flow_control.domain.health;

import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;

import java.time.Duration;

/** 평소 응답 시간의 종류. 평균과 p95 를 따로 배운다. */
public enum BaselineKind {
    MEAN("mean"),
    P95("p95");

    private final String label;

    BaselineKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public Duration policyValueOf(TargetSystemPolicy limits) {
        return this == MEAN ? limits.baselineRtt() : limits.baselineP95();
    }
}
