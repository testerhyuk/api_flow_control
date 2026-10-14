package com.hyuk.flow_control.domain.policy;

import java.time.Instant;

public record PolicyHistoryEntry(
        ManagedPolicy policy,
        Instant changedAt,
        ChangeNote note
) {
    public PolicyHistoryEntry {
        if (policy == null || changedAt == null || note == null) {
            throw new IllegalArgumentException("이력의 정책, 시각, 변경 내용은 필수입니다");
        }
    }
}
