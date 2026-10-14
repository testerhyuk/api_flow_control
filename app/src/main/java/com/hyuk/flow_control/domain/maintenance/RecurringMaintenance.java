package com.hyuk.flow_control.domain.maintenance;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record RecurringMaintenance(
        Set<DayOfWeek> days,
        LocalTime start,
        LocalTime end
) {
    public RecurringMaintenance {
        if (days == null || days.isEmpty() || days.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("반복 점검의 요일은 하나 이상이어야 합니다");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("반복 점검의 시작 시각과 끝 시각은 필수입니다");
        }
        if (start.equals(end)) {
            throw new IllegalArgumentException("반복 점검의 시작 시각과 끝 시각이 같습니다 : " + start);
        }
        days = Collections.unmodifiableSet(EnumSet.copyOf(days));
    }

    public boolean crossesMidnight() {
        return end.isBefore(start);
    }

    public Optional<LocalDateTime> endOfWindowAt(LocalDateTime at) {
        LocalTime time = at.toLocalTime();
        DayOfWeek day = at.getDayOfWeek();

        if (!crossesMidnight()) {
            if (days.contains(day) && !time.isBefore(start) && time.isBefore(end)) {
                return Optional.of(at.toLocalDate().atTime(end));
            }
            return Optional.empty();
        }

        if (days.contains(day) && !time.isBefore(start)) {
            return Optional.of(at.toLocalDate().plusDays(1).atTime(end));
        }
        if (days.contains(day.minus(1)) && time.isBefore(end)) {
            return Optional.of(at.toLocalDate().atTime(end));
        }
        return Optional.empty();
    }
}
