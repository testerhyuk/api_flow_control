package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.health.RecordedOutcome;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class TargetSystemOutcomeRecorder {
    private final Map<String, Deque<RecordedOutcome>> outcomes;
    private final Duration window;

    public TargetSystemOutcomeRecorder(
            TargetSystemPolicyReader policyReader,
            HealthProperties healthProperties
            ) {
        Map<String, Deque<RecordedOutcome>> created = new HashMap<>();

        policyReader.readAll().keySet().forEach(targetSystemId -> {
            created.put(targetSystemId, new ConcurrentLinkedDeque<>());
        });

        this.outcomes = Map.copyOf(created);
        this.window = healthProperties.window();
    }

    public void record(String targetSystemId, OutcomeKind kind, Duration latency) {
        outcomesOf(targetSystemId).addLast(new RecordedOutcome(kind, latency, Instant.now()));
    }

    public List<RecordedOutcome> recentOutcomes(String targetSystemId) {
        Deque<RecordedOutcome> deque = outcomesOf(targetSystemId);
        Instant threshold = Instant.now().minus(window);

        removeOlderThan(deque, threshold);

        return deque.stream()
                .filter(outcome -> !outcome.recordedAt().isBefore(threshold))
                .toList();
    }

    private void removeOlderThan(Deque<RecordedOutcome> deque, Instant threshold) {
        RecordedOutcome oldest = deque.peekFirst();

        while (oldest != null && oldest.recordedAt().isBefore(threshold)) {
            deque.pollFirst();
            oldest = deque.peekFirst();
        }
    }

    private Deque<RecordedOutcome> outcomesOf(String targetSystemId) {
        Deque<RecordedOutcome> deque = outcomes.get(targetSystemId);

        if (deque == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다: " + targetSystemId);
        }

        return deque;
    }
}
