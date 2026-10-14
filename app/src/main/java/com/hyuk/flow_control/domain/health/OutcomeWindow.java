package com.hyuk.flow_control.domain.health;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public record OutcomeWindow(
        List<RecordedOutcome> outcomes,
        Duration span
) {
    private static final OutcomeWindow EMPTY = new OutcomeWindow(List.of(), null);

    public OutcomeWindow {
        outcomes = List.copyOf(outcomes);
    }

    public static OutcomeWindow empty() {
        return EMPTY;
    }

    public static OutcomeWindow latest(Iterator<RecordedOutcome> newestFirst, int maxSamples, Instant since) {
        List<RecordedOutcome> picked = new ArrayList<>();
        int samples = 0;

        while (newestFirst.hasNext() && samples < maxSamples) {
            RecordedOutcome outcome = newestFirst.next();

            if (outcome.recordedAt().isBefore(since)) {
                break;
            }
            if (!OutcomeStatistics.isSample(outcome.kind())) {
                continue;
            }

            picked.add(outcome);
            samples++;
        }

        if (picked.isEmpty()) {
            return EMPTY;
        }

        Collections.reverse(picked);
        Duration span = Duration.between(picked.getFirst().recordedAt(), picked.getLast().recordedAt());
        return new OutcomeWindow(picked, span);
    }

    public OutcomeStatistics statistics() {
        return OutcomeStatistics.from(outcomes);
    }
}
