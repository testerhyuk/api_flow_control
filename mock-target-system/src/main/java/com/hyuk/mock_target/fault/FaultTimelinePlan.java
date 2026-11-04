package com.hyuk.mock_target.fault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record FaultTimelinePlan(List<Entry> faults) {
    public FaultTimelinePlan {
        if (faults == null || faults.isEmpty() || faults.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("faults 는 하나 이상 있어야 합니다");
        }

        faults.forEach(FaultTimelinePlan::validate);
        faults = faults.stream().sorted(Comparator.comparing(Entry::atSeconds)).toList();
        rejectOverlaps(faults);
    }

    public List<Step> steps() {
        List<Step> steps = new ArrayList<>();
        for (Entry entry : faults) {
            FaultType type = FaultType.fromId(entry.type());
            steps.add(new Step(entry.atSeconds(), Action.APPLY, type, entry.value()));
            steps.add(new Step(entry.clearAtSeconds(), Action.CLEAR, type, null));
        }

        steps.sort(Comparator.comparingLong(Step::atSeconds).thenComparing(Step::action));
        return List.copyOf(steps);
    }

    private static void validate(Entry entry) {
        FaultType type = FaultType.fromId(entry.type());
        if (entry.atSeconds() == null || entry.atSeconds() < 0) {
            throw new IllegalArgumentException("atSeconds 는 0 이상이어야 합니다");
        }
        if (entry.clearAtSeconds() == null || entry.clearAtSeconds() <= entry.atSeconds()) {
            throw new IllegalArgumentException("clearAtSeconds 는 atSeconds 보다 커야 합니다");
        }

        new Faults().apply(type, entry.value());
    }

    private static void rejectOverlaps(List<Entry> sorted) {
        Map<String, Long> lastClearAt = new HashMap<>();
        for (Entry entry : sorted) {
            Long previousClearAt = lastClearAt.put(entry.type(), entry.clearAtSeconds());
            if (previousClearAt != null && entry.atSeconds() < previousClearAt) {
                throw new IllegalArgumentException("같은 장애의 구간이 겹칩니다 : " + entry.type());
            }
        }
    }

    public record Entry(Long atSeconds, String type, Double value, Long clearAtSeconds) {
    }

    // 같은 시각에는 CLEAR 가 먼저 실행되도록 순서를 둔다.
    public enum Action {
        CLEAR,
        APPLY
    }

    public record Step(long atSeconds, Action action, FaultType type, Double value) {
    }
}
