package com.hyuk.record_ingestor;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ExperimentPlan(
        long seed,
        Map<String, Double> rates,
        List<RateChange> timeline,
        long durationSeconds
) {
    public ExperimentPlan {
        if (rates == null || rates.isEmpty()) {
            throw new IllegalArgumentException("요청을 보낼 대상이 하나 이상 있어야 합니다");
        }
        rates.forEach((targetSystemId, rate) -> {
            if (rate == null || rate <= 0) {
                throw new IllegalArgumentException("rate 는 0보다 커야 합니다 : " + targetSystemId);
            }
        });
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds 는 0보다 커야 합니다");
        }

        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        validate(timeline, durationSeconds);
        timeline = timeline.stream().sorted(Comparator.comparing(RateChange::atSeconds)).toList();
        rates = Map.copyOf(rates);
    }

    public Set<String> targets() {
        Set<String> targets = new LinkedHashSet<>(rates.keySet());
        timeline.forEach(change -> targets.add(change.target()));
        return targets;
    }

    public List<RateChange> changesOf(String targetSystemId) {
        return timeline.stream().filter(change -> change.target().equals(targetSystemId)).toList();
    }

    private static void validate(List<RateChange> timeline, long durationSeconds) {
        Set<String> seen = new HashSet<>();

        for (RateChange change : timeline) {
            if (change.target() == null || change.target().isBlank()) {
                throw new IllegalArgumentException("시간표의 target 은 필수입니다");
            }
            if (change.atSeconds() == null || change.atSeconds() <= 0 || change.atSeconds() >= durationSeconds) {
                throw new IllegalArgumentException(
                        "시간표의 atSeconds 는 0보다 크고 durationSeconds 보다 작아야 합니다 : " + change);
            }
            if (change.rate() == null || change.rate() < 0) {
                throw new IllegalArgumentException("시간표의 rate 는 0 이상이어야 합니다 : " + change);
            }
            if (!seen.add(change.target() + "@" + change.atSeconds())) {
                throw new IllegalArgumentException("같은 대상에 같은 시각이 두 번 있습니다 : " + change);
            }
        }
    }

    public record RateChange(Long atSeconds, String target, Double rate) {
    }
}
