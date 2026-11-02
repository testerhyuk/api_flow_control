package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.application.port.in.DryRunStatus;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunReport;
import com.hyuk.flow_control.domain.health.HealthState;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class DryRunViews {
    private DryRunViews() {
    }

    public record Status(
            String targetSystemId,
            long baseVersion,
            Instant startedAt,
            Instant expiresAt,
            String startedBy,
            String reason,
            PolicyBody policy,
            Result result
    ) {
        static Status from(DryRun dryRun) {
            return from(new DryRunStatus(dryRun, null));
        }

        static Status from(DryRunStatus status) {
            DryRun dryRun = status.dryRun();

            return new Status(
                    dryRun.targetSystemId(),
                    dryRun.baseVersion(),
                    dryRun.startedAt(),
                    dryRun.expiresAt(),
                    dryRun.note().changedBy(),
                    dryRun.note().reason(),
                    PolicyBody.from(dryRun.candidate()),
                    status.report() == null ? null : Result.from(status.report()));
        }
    }

    public record Result(
            Instant observedSince,
            Instant reportedAt,
            long observedCalls,
            long overlapMs,
            DryRunReport.Limits limits,
            List<DryRunReport.JudgmentChange> judgmentChanges,
            DryRunReport.Timeouts timeouts,
            Health health,
            Maintenance maintenance,
            List<String> unknowns
    ) {
        static Result from(DryRunReport report) {
            return new Result(
                    report.observedSince(),
                    report.reportedAt(),
                    report.observedCalls(),
                    report.overlap().toMillis(),
                    report.limits(),
                    report.judgmentChanges(),
                    report.timeouts(),
                    Health.from(report.health()),
                    report.maintenance() == null ? null : Maintenance.from(report.maintenance()),
                    report.unknowns());
        }
    }

    public record Maintenance(
            long candidateTimeMs,
            long candidateCalls,
            long addedTimeMs,
            long removedTimeMs
    ) {
        static Maintenance from(DryRunReport.Maintenance maintenance) {
            return new Maintenance(
                    maintenance.candidateTime().toMillis(),
                    maintenance.candidateCalls(),
                    maintenance.addedTime().toMillis(),
                    maintenance.removedTime().toMillis());
        }
    }

    public record Health(
            HealthState actualState,
            HealthState state,
            long transitions,
            Map<HealthState, Long> timeInStateMs,
            int sampleCount,
            double failureRate,
            Long p95Ms
    ) {
        static Health from(DryRunReport.Health health) {
            Map<HealthState, Long> times = new EnumMap<>(HealthState.class);
            health.timeInState().forEach((state, duration) -> times.put(state, duration.toMillis()));

            Duration p95 = health.p95();

            return new Health(
                    health.actualState(),
                    health.state(),
                    health.transitions(),
                    times,
                    health.sampleCount(),
                    health.failureRate(),
                    p95 == null ? null : p95.toMillis());
        }
    }
}
