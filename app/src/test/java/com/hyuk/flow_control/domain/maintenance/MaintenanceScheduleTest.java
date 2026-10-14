package com.hyuk.flow_control.domain.maintenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaintenanceScheduleTest {
    private static final RecurringMaintenance NIGHTLY = new RecurringMaintenance(
            Set.of(DayOfWeek.WEDNESDAY), LocalTime.of(23, 50), LocalTime.of(0, 10));
    private static final RecurringMaintenance SUNDAY_MORNING = new RecurringMaintenance(
            Set.of(DayOfWeek.SUNDAY), LocalTime.of(2, 0), LocalTime.of(4, 0));
    private static final OneTimeMaintenance SYSTEM_CHANGE = new OneTimeMaintenance(
            LocalDateTime.of(2026, 10, 20, 1, 0), LocalDateTime.of(2026, 10, 20, 5, 0));

    @Test
    @DisplayName("시각은 한국 시간으로 본다")
    void usesSeoulTime() {
        MaintenanceSchedule schedule = new MaintenanceSchedule(List.of(SUNDAY_MORNING), List.of());

        assertThat(schedule.isActiveAt(seoul(2026, 10, 18, 2, 30))).isTrue();
        assertThat(schedule.isActiveAt(Instant.parse("2026-10-18T02:30:00Z"))).isFalse();
    }

    @Test
    @DisplayName("반복 점검은 정한 요일의 시작 시각부터 끝 시각 전까지다")
    void recurringWindow() {
        MaintenanceSchedule schedule = new MaintenanceSchedule(List.of(SUNDAY_MORNING), List.of());

        assertThat(schedule.isActiveAt(seoul(2026, 10, 18, 1, 59))).isFalse();
        assertThat(schedule.isActiveAt(seoul(2026, 10, 18, 2, 0))).isTrue();
        assertThat(schedule.isActiveAt(seoul(2026, 10, 18, 4, 0))).isFalse();
        assertThat(schedule.isActiveAt(seoul(2026, 10, 19, 3, 0))).isFalse();
        assertThat(schedule.endOfWindowAt(seoul(2026, 10, 18, 3, 0))).contains(seoul(2026, 10, 18, 4, 0));
    }

    @Test
    @DisplayName("자정을 넘는 반복 점검은 시작한 요일 다음 날 끝 시각까지 이어진다")
    void crossesMidnight() {
        MaintenanceSchedule schedule = new MaintenanceSchedule(List.of(NIGHTLY), List.of());

        assertThat(schedule.isActiveAt(seoul(2026, 10, 14, 23, 49))).isFalse();
        assertThat(schedule.isActiveAt(seoul(2026, 10, 14, 23, 55))).isTrue();
        assertThat(schedule.isActiveAt(seoul(2026, 10, 15, 0, 5))).isTrue();
        assertThat(schedule.isActiveAt(seoul(2026, 10, 15, 0, 10))).isFalse();
        assertThat(schedule.isActiveAt(seoul(2026, 10, 14, 0, 5))).isFalse();
        assertThat(schedule.endOfWindowAt(seoul(2026, 10, 14, 23, 55))).contains(seoul(2026, 10, 15, 0, 10));
    }

    @Test
    @DisplayName("특정 날짜 점검은 시작 일시부터 끝 일시 전까지다")
    void oneTimeWindow() {
        MaintenanceSchedule schedule = new MaintenanceSchedule(List.of(), List.of(SYSTEM_CHANGE));

        assertThat(schedule.isActiveAt(seoul(2026, 10, 20, 0, 59))).isFalse();
        assertThat(schedule.isActiveAt(seoul(2026, 10, 20, 1, 0))).isTrue();
        assertThat(schedule.isActiveAt(seoul(2026, 10, 20, 5, 0))).isFalse();
    }

    @Test
    @DisplayName("점검 시간표가 없으면 늘 점검 중이 아니다")
    void none() {
        assertThat(MaintenanceSchedule.none().isEmpty()).isTrue();
        assertThat(MaintenanceSchedule.none().isActiveAt(Instant.now())).isFalse();
        assertThat(new MaintenanceSchedule(null, null)).isEqualTo(MaintenanceSchedule.none());
    }

    @Test
    @DisplayName("잘못된 점검 시간은 만들 수 없다")
    void rejectsInvalid() {
        assertThatThrownBy(() -> new RecurringMaintenance(Set.of(), LocalTime.of(1, 0), LocalTime.of(2, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecurringMaintenance(Set.of(DayOfWeek.MONDAY), LocalTime.of(1, 0), LocalTime.of(1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecurringMaintenance(Set.of(DayOfWeek.MONDAY), null, LocalTime.of(1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OneTimeMaintenance(
                LocalDateTime.of(2026, 10, 20, 5, 0), LocalDateTime.of(2026, 10, 20, 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Instant seoul(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(MaintenanceSchedule.ZONE).toInstant();
    }
}
