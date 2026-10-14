package com.hyuk.flow_control.domain.dryrun;

import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.request.OutcomeKind;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DryRunReport(
        Instant observedSince,
        Instant reportedAt,
        long observedCalls,
        Duration overlap,
        Limits limits,
        List<JudgmentChange> judgmentChanges,
        Timeouts timeouts,
        Health health,
        List<String> unknowns
) {
    public DryRunReport {
        judgmentChanges = List.copyOf(judgmentChanges);
        unknowns = List.copyOf(unknowns);
    }

    public record Limits(
            double actualRate,
            double rate,
            int concurrencyLimit,
            long wouldBlockBySemaphore,
            long wouldBlockByTokenBucket,
            long waitedByCurrentLimit
    ) {
    }

    public record JudgmentChange(OutcomeKind actual, OutcomeKind candidate, long count) {
    }

    public record Timeouts(long wouldTimeout, long actualTimeoutsWithUnknownResult) {
    }

    public record Health(
            HealthState actualState,
            HealthState state,
            long transitions,
            Map<HealthState, Duration> timeInState,
            int sampleCount,
            double failureRate,
            Duration p95
    ) {
        public Health {
            timeInState = Map.copyOf(timeInState);
        }
    }
}
