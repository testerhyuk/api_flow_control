package com.hyuk.flow_control.domain.health;

/** 배운 평소 응답 시간이 정책 범위의 어느 끝에 걸렸는지. */
public enum BaselineBound {
    LOWER("lower"),
    UPPER("upper");

    private final String label;

    BaselineBound(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
