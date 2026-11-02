package com.hyuk.flow_control.domain.health;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

public final class HealthTracker {
    private final HealthRules rules;
    private final int minimumCalls;
    private final LowTrafficRules lowTraffic;

    private HealthStatus status;
    private HealthMode mode = HealthMode.ADAPTIVE;
    private int lowTrafficTicks;
    private Integer quietCalls;

    public HealthTracker(HealthRules rules, int minimumCalls, LowTrafficRules lowTraffic, HealthStatus initial) {
        this.rules = rules;
        this.minimumCalls = minimumCalls;
        this.lowTraffic = lowTraffic;
        this.status = initial;
    }

    public synchronized HealthStatus status() {
        return status;
    }

    public synchronized HealthMode mode() {
        return mode;
    }

    public synchronized Step judge(OutcomeStatistics statistics, Duration baselineP95, Set<LowTrafficEvent> events,
                                   int samples, BooleanSupplier blockedRecently) {
        HealthStatus before = status;
        HealthMode modeBefore = mode;
        boolean enough = statistics.sampleCount() >= minimumCalls;

        if (mode == HealthMode.ADAPTIVE && !enough) {
            lowTrafficTicks = blockedRecently.getAsBoolean() ? 0 : lowTrafficTicks + 1;

            if (lowTrafficTicks >= lowTraffic.enterAfter()) {
                lowTrafficTicks = 0;
                mode = HealthMode.LOW_TRAFFIC;
                if (before.state() == HealthState.CRITICAL) {
                    samples = 0;
                    quietCalls = 0;
                }
            }
        } else if (mode == HealthMode.ADAPTIVE) {
            lowTrafficTicks = 0;
        } else if (enough) {
            mode = HealthMode.ADAPTIVE;
        }

        if (mode == HealthMode.LOW_TRAFFIC) {
            status = inLowTraffic(before, events, samples);
            return new Step(before, status, modeBefore, mode, Optional.empty(), Optional.empty(), events, samples);
        }

        HealthRules.Judgment judgment = rules.judge(before, statistics, baselineP95, samples);
        status = judgment.next();
        return new Step(before, status, modeBefore, mode, judgment.grade(), judgment.suppressedBy(), Set.of(), samples);
    }

    public synchronized void pause() {
        lowTrafficTicks = 0;
    }

    public synchronized HealthMode restartRecovering() {
        HealthMode before = mode;
        status = HealthStatus.of(HealthState.RECOVERING);
        lowTrafficTicks = 0;
        mode = HealthMode.ADAPTIVE;
        return before;
    }

    private HealthStatus inLowTraffic(HealthStatus current, Set<LowTrafficEvent> events, int samples) {
        if (!events.isEmpty()) {
            quietCalls = 0;
            return HealthStatus.of(HealthState.CRITICAL);
        }

        if (current.state() == HealthState.CRITICAL && quietCalls != null) {
            quietCalls += samples;
            if (quietCalls >= lowTraffic.recoveryCalls()) {
                quietCalls = null;
                return HealthStatus.initial();
            }
        }

        return current.withNewSamples(samples);
    }

    public record Step(
            HealthStatus before,
            HealthStatus next,
            HealthMode modeBefore,
            HealthMode mode,
            Optional<HealthGrade> grade,
            Optional<SuppressedBy> suppressedBy,
            Set<LowTrafficEvent> events,
            int samples
    ) {
        public boolean modeChanged() {
            return modeBefore != mode;
        }

        public boolean stateChanged() {
            return before.state() != next.state();
        }

        public boolean criticalByEvents() {
            return !events.isEmpty();
        }
    }
}
