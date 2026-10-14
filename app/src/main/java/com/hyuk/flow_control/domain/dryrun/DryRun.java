package com.hyuk.flow_control.domain.dryrun;

import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;

import java.time.Instant;

public record DryRun(
        ManagedPolicy candidate,
        long baseVersion,
        Instant startedAt,
        Instant expiresAt,
        ChangeNote note
) {
    public DryRun {
        if (candidate == null || startedAt == null || expiresAt == null || note == null) {
            throw new IllegalArgumentException("dry-run 의 새 정책, 시작 시각, 끝 시각, 시작 내용은 필수입니다");
        }
        if (candidate.version() != baseVersion + 1) {
            throw new IllegalArgumentException("새 정책의 버전은 기준 버전 다음이어야 합니다");
        }
        if (!expiresAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("dry-run 끝 시각은 시작 시각보다 뒤여야 합니다");
        }
    }

    public String targetSystemId() {
        return candidate.targetSystemId();
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
