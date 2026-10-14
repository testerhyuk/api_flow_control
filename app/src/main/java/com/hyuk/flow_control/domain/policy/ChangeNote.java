package com.hyuk.flow_control.domain.policy;

public record ChangeNote(
        String changedBy,
        String reason
) {
    public ChangeNote {
        if (changedBy == null || changedBy.isBlank()) {
            throw new IllegalArgumentException("바꾼 사람(changedBy)은 필수입니다");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("변경 사유(reason)는 필수입니다");
        }
    }
}
