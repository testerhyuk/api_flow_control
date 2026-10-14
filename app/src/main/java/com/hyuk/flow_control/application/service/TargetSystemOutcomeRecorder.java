package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.LowTrafficEvent;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
@Order(0)
public class TargetSystemOutcomeRecorder implements PolicyChangeListener {
    private final Map<String, Outcomes> outcomes = new ConcurrentHashMap<>();
    private final Duration window;
    private final int maxSamples;
    private final HealthProperties.LowTraffic lowTraffic;

    public TargetSystemOutcomeRecorder(
            TargetSystemPolicyReader policyReader,
            HealthProperties healthProperties
            ) {
        this.window = healthProperties.window();
        this.maxSamples = healthProperties.minimumCalls();
        this.lowTraffic = healthProperties.lowTraffic();

        policyReader.readAll().keySet().forEach(targetSystemId ->
                outcomes.put(targetSystemId, new Outcomes()));
    }

    @Override
    public void onPolicyAdded(ManagedPolicy policy) {
        outcomes.putIfAbsent(policy.targetSystemId(), new Outcomes());
    }

    @Override
    public void onPolicyChanged(ManagedPolicy before, ManagedPolicy after) {
    }

    public void record(String targetSystemId, OutcomeKind kind, Duration latency) {
        Outcomes target = outcomesOf(targetSystemId);
        target.deque.addLast(new RecordedOutcome(kind, latency, Instant.now()));
        target.track(kind, lowTraffic);
    }

    public void recordBlocked(String targetSystemId) {
        outcomesOf(targetSystemId).lastBlockedAt = Instant.now();
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
        Instant blockedAt = outcomesOf(targetSystemId).lastBlockedAt;
        return blockedAt != null && !blockedAt.isBefore(Instant.now().minus(window));
    }

    public Set<LowTrafficEvent> drainEvents(String targetSystemId) {
        return outcomesOf(targetSystemId).drain();
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
        private final Set<LowTrafficEvent> pending = EnumSet.noneOf(LowTrafficEvent.class);
        private int tooManyRequests;
        private int failureStreak;
        private int timeoutStreak;
        private volatile Instant lastBlockedAt;

        private synchronized void track(OutcomeKind kind, HealthProperties.LowTraffic rules) {
            if (!OutcomeStatistics.isSample(kind)) {
                return;
            }

            if (kind == OutcomeKind.TOO_MANY_REQUESTS && ++tooManyRequests >= rules.tooManyRequests()) {
                pending.add(LowTrafficEvent.TOO_MANY_REQUESTS);
                tooManyRequests = 0;
            }

            if (!OutcomeStatistics.isFailure(kind)) {
                failureStreak = 0;
                timeoutStreak = 0;
                return;
            }

            if (++failureStreak >= rules.consecutiveFailures()) {
                pending.add(LowTrafficEvent.CONSECUTIVE_FAILURES);
                failureStreak = 0;
            }

            if (kind != OutcomeKind.TIMEOUT) {
                timeoutStreak = 0;
            } else if (++timeoutStreak >= rules.consecutiveTimeouts()) {
                pending.add(LowTrafficEvent.CONSECUTIVE_TIMEOUTS);
                timeoutStreak = 0;
            }
        }

        private synchronized Set<LowTrafficEvent> drain() {
            if (pending.isEmpty()) {
                return Set.of();
            }

            Set<LowTrafficEvent> drained = Set.copyOf(pending);
            pending.clear();
            return drained;
        }
    }
}
