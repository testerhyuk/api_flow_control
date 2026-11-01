package com.hyuk.flow_control.domain.requester;

import com.hyuk.flow_control.domain.policy.ChangeNote;

import java.time.Instant;

public record RequesterHistoryEntry(
        Requester requester,
        Instant changedAt,
        ChangeNote note
) {
    public RequesterHistoryEntry {
        if (requester == null || changedAt == null || note == null) {
            throw new IllegalArgumentException("이력의 요청자, 시각, 변경 내용은 필수입니다");
        }
    }
}
