package com.hyuk.flow_control.domain.health;

import com.hyuk.flow_control.domain.request.OutcomeKind;

import java.time.Duration;
import java.time.Instant;

public record RecordedOutcome(
        OutcomeKind kind,
        Duration latency,
        Instant recordedAt
) {
    public RecordedOutcome {
        if (kind == null) {
            throw new IllegalArgumentException("kind는 필수입니다");
        }
        if (latency == null || latency.isNegative()) {
            throw new IllegalArgumentException("latency는 0 이상이어야 합니다");
        }
        if (recordedAt == null) {
            throw new IllegalArgumentException("기록 시점은 필수입니다");
        }
    }
}
