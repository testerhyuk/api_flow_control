package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.settings.HealthSettings;
import com.hyuk.flow_control.domain.health.LowTrafficEvent;
import com.hyuk.flow_control.domain.health.LowTrafficRules;
import com.hyuk.flow_control.domain.health.LowTrafficStreaks;
import com.hyuk.flow_control.domain.health.OutcomeStatistics;
import com.hyuk.flow_control.domain.health.OutcomeWindow;
import com.hyuk.flow_control.domain.health.RecordedOutcome;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Order(0)
public class TargetSystemOutcomeRecorder implements PolicyChangeListener {
    private final Map<String, Outcomes> outcomes = new ConcurrentHashMap<>();
    private final Duration window;
    private final int maxSamples;
    private final LowTrafficRules lowTraffic;

    public TargetSystemOutcomeRecorder(
            TargetSystemPolicyReader policyReader,
            HealthSettings healthSettings
            ) {
        this.window = healthSettings.window();
        this.maxSamples = healthSettings.minimumCalls();
        this.lowTraffic = healthSettings.lowTraffic();

        policyReader.readAll().keySet().forEach(targetSystemId ->
                outcomes.put(targetSystemId, new Outcomes(lowTraffic)));
    }

    @Override
    public void onPolicyAdded(ManagedPolicy policy) {
        outcomes.putIfAbsent(policy.targetSystemId(), new Outcomes(lowTraffic));
    }

    @Override
    public void onPolicyChanged(ManagedPolicy before, ManagedPolicy after) {
    }

    public void record(String targetSystemId, OutcomeKind kind, Duration latency) {
        Outcomes target = outcomesOf(targetSystemId);
        target.deque.addLast(new RecordedOutcome(kind, latency, Instant.now()));
        target.track(kind);
    }

    public void recordBlocked(String targetSystemId) {
        outcomesOf(targetSystemId).lastBlockedAt = Instant.now();
    }

    public void waitStarted(String targetSystemId) {
        outcomesOf(targetSystemId).waiting.incrementAndGet();
    }

    public void waitEnded(String targetSystemId) {
        Outcomes target = outcomesOf(targetSystemId);
        target.lastBlockedAt = Instant.now();
        target.waiting.decrementAndGet();
    }

    public List<RecordedOutcome> recentOutcomes(String targetSystemId) {
        return window(targetSystemId).outcomes();
    }

    public OutcomeWindow window(String targetSystemId) {
        Deque<RecordedOutcome> deque = outcomesOf(targetSystemId).deque;
        Instant threshold = Instant.now().minus(window);

        removeOlderThan(deque, threshold);

        return OutcomeWindow.latest(deque.descendingIterator(), maxSamples, threshold);
    }

    public boolean blockedWithinWindow(String targetSystemId) {
        Outcomes target = outcomesOf(targetSystemId);
        if (target.waiting.get() > 0) {
            return true;
        }

        Instant blockedAt = target.lastBlockedAt;
        return blockedAt != null && !blockedAt.isBefore(Instant.now().minus(window));
    }

    public Set<LowTrafficEvent> drainEvents(String targetSystemId) {
        return outcomesOf(targetSystemId).drain();
    }

    public int drainNewSamples(String targetSystemId) {
        return outcomesOf(targetSystemId).newSamples.getAndSet(0);
    }

    private void removeOlderThan(Deque<RecordedOutcome> deque, Instant threshold) {
        RecordedOutcome oldest = deque.peekFirst();

        while (oldest != null && oldest.recordedAt().isBefore(threshold)) {
            deque.pollFirst();
            oldest = deque.peekFirst();
        }
    }

    private Outcomes outcomesOf(String targetSystemId) {
        Outcomes target = outcomes.get(targetSystemId);

        if (target == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다: " + targetSystemId);
        }

        return target;
    }

    private static final class Outcomes {
        private final Deque<RecordedOutcome> deque = new ConcurrentLinkedDeque<>();
        private final LowTrafficStreaks streaks;
        private final AtomicInteger waiting = new AtomicInteger();
        private final AtomicInteger newSamples = new AtomicInteger();
        private volatile Instant lastBlockedAt;

        private Outcomes(LowTrafficRules lowTraffic) {
            this.streaks = new LowTrafficStreaks(lowTraffic);
        }

        private void track(OutcomeKind kind) {
            if (OutcomeStatistics.isSample(kind)) {
                newSamples.incrementAndGet();
            }
            streaks.track(kind);
        }

        private Set<LowTrafficEvent> drain() {
            return streaks.drain();
        }
    }
}
