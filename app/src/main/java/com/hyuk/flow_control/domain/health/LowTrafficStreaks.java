package com.hyuk.flow_control.domain.health;

import com.hyuk.flow_control.domain.request.OutcomeKind;

import java.util.EnumSet;
import java.util.Set;

public final class LowTrafficStreaks {
    private final LowTrafficRules rules;
    private final Set<LowTrafficEvent> pending = EnumSet.noneOf(LowTrafficEvent.class);
    private int failureStreak;
    private int timeoutStreak;

    public LowTrafficStreaks(LowTrafficRules rules) {
        this.rules = rules;
    }

    public synchronized void track(OutcomeKind kind) {
        if (!OutcomeStatistics.isSample(kind)) {
            return;
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

    public synchronized Set<LowTrafficEvent> drain() {
        if (pending.isEmpty()) {
            return Set.of();
        }

        Set<LowTrafficEvent> drained = Set.copyOf(pending);
        pending.clear();
        return drained;
    }
}
