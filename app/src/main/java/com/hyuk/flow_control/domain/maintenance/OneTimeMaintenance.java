package com.hyuk.flow_control.domain.maintenance;

import java.time.LocalDateTime;
import java.util.Optional;

public record OneTimeMaintenance(
        LocalDateTime start,
        LocalDateTime end
) {
    public OneTimeMaintenance {
        if (start == null || end == null) {
            throw new IllegalArgumentException("특정 날짜 점검의 시작 일시와 끝 일시는 필수입니다");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("특정 날짜 점검의 끝 일시는 시작 일시보다 뒤여야 합니다");
        }
    }

    public Optional<LocalDateTime> endOfWindowAt(LocalDateTime at) {
        if (!at.isBefore(start) && at.isBefore(end)) {
            return Optional.of(end);
        }
        return Optional.empty();
    }
}
