package com.hyuk.flow_control.domain.dryrun;

import java.time.Instant;

public record DryRunResult(
        DryRun dryRun,
        DryRunEnd end,
        Instant endedAt,
        String endedBy,
        Long appliedVersion,
        DryRunReport report
) {
    public DryRunResult {
        if (dryRun == null || end == null || endedAt == null) {
            throw new IllegalArgumentException("dry-run 결과에는 dry-run, 끝난 이유, 끝난 시각이 필요합니다");
        }
        if ((end == DryRunEnd.APPLIED) != (appliedVersion != null)) {
            throw new IllegalArgumentException("적용으로 끝났을 때만 적용한 정책 버전을 남깁니다");
        }
    }
}
