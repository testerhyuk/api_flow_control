package com.hyuk.mock_target.simulation;

public record CallResult(
        Outcome outcome,
        Long retryAfterSeconds
) {
    public CallResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 은 필수입니다");
        }
        if (retryAfterSeconds != null && retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds 는 0 이상이어야 합니다");
        }
    }

    public static CallResult of(Outcome outcome) {
        return new CallResult(outcome, null);
    }
}
