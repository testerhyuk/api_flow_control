package com.hyuk.flow_control.domain.maintenance;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public record MaintenanceSchedule(
        List<RecurringMaintenance> recurring,
        List<OneTimeMaintenance> oneTime
) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final MaintenanceSchedule NONE = new MaintenanceSchedule(List.of(), List.of());

    public MaintenanceSchedule {
        recurring = recurring == null ? List.of() : List.copyOf(recurring);
        oneTime = oneTime == null ? List.of() : List.copyOf(oneTime);
    }

    public static MaintenanceSchedule none() {
        return NONE;
    }

    public boolean isEmpty() {
        return recurring.isEmpty() && oneTime.isEmpty();
    }

    public boolean isActiveAt(Instant at) {
        return endOfWindowAt(at).isPresent();
    }

    public Optional<Instant> endOfWindowAt(Instant at) {
        LocalDateTime local = LocalDateTime.ofInstant(at, ZONE);

        return Stream.concat(
                        recurring.stream().map(window -> window.endOfWindowAt(local)),
                        oneTime.stream().map(window -> window.endOfWindowAt(local)))
                .flatMap(Optional::stream)
                .max(LocalDateTime::compareTo)
                .map(end -> end.atZone(ZONE).toInstant());
    }
}
