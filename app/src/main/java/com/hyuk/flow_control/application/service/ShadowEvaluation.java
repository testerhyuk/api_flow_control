package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.ResponseJudge;
import com.hyuk.flow_control.domain.dryrun.CountingTokenBucket;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunReport;
import com.hyuk.flow_control.domain.health.HealthGrade;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.HealthStatus;
import com.hyuk.flow_control.domain.health.HealthThresholds;
import com.hyuk.flow_control.domain.health.OutcomeStatistics;
import com.hyuk.flow_control.domain.health.RecordedOutcome;
import com.hyuk.flow_control.domain.limit.RateAdjustmentRule;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

class ShadowEvaluation {
    private final DryRun dryRun;
    private final ManagedPolicy base;
    private final ManagedPolicy candidate;
    private final ResponseJudge judge;
    private final HealthThresholds thresholds;
    private final RateAdjustmentRule rateRule;
    private final int requiredGoodCount;
    private final Duration window;
    private final FlowControlMetrics metrics;
    private final Instant observedSince;

    private final Deque<RecordedOutcome> outcomes = new ConcurrentLinkedDeque<>();
    private final CountingTokenBucket bucket;
    private final AtomicInteger inFlight = new AtomicInteger();

    private final LongAdder observedCalls = new LongAdder();
    private final LongAdder wouldBlockBySemaphore = new LongAdder();
    private final LongAdder wouldBlockByTokenBucket = new LongAdder();
    private final LongAdder waitedByCurrentLimit = new LongAdder();
    private final LongAdder wouldTimeout = new LongAdder();
    private final LongAdder unknownTimeouts = new LongAdder();
    private final Map<JudgmentKey, LongAdder> judgmentChanges = new ConcurrentHashMap<>();

    private final Map<HealthState, Duration> timeInState = new EnumMap<>(HealthState.class);
    private volatile HealthStatus status;
    private volatile OutcomeStatistics statistics = OutcomeStatistics.from(List.of());
    private volatile double rate;
    private volatile int concurrencyLimit;
    private long transitions;
    private Duration overlap = Duration.ZERO;
    private Instant lastEvaluatedAt;

    ShadowEvaluation(DryRun dryRun, ManagedPolicy base, HealthStatus actualStatus, double actualRate,
                     ResponseJudge judge, HealthThresholds thresholds, RateAdjustmentRule rateRule,
                     int requiredGoodCount, Duration window, FlowControlMetrics metrics,
                     Instant now, long nowNanos) {
        if (base.version() != dryRun.baseVersion()) {
            throw new IllegalArgumentException("dry-run 기준 버전과 지금 정책 버전이 다릅니다");
        }

        this.dryRun = dryRun;
        this.base = base;
        this.candidate = dryRun.candidate();
        this.judge = judge;
        this.thresholds = thresholds;
        this.rateRule = rateRule;
        this.requiredGoodCount = requiredGoodCount;
        this.window = window;
        this.metrics = metrics;
        this.observedSince = now;
        this.lastEvaluatedAt = now;

        TargetSystemPolicy limits = candidate.limits();
        this.status = actualStatus;
        this.rate = Math.min(actualRate, limits.effectiveRate());
        this.concurrencyLimit = limits.concurrencyLimitAt(rate);
        this.bucket = new CountingTokenBucket(rate, limits.burstCapacityAt(rate), nowNanos);
    }

    DryRun dryRun() {
        return dryRun;
    }

    void admit(long nowNanos) {
        String targetSystemId = dryRun.targetSystemId();
        int running = inFlight.incrementAndGet();
        observedCalls.increment();
        metrics.recordDryRunObserved(targetSystemId);

        if (running > concurrencyLimit) {
            wouldBlockBySemaphore.increment();
            metrics.recordDryRunBlockedBySemaphore(targetSystemId);
            return;
        }
        if (!bucket.tryConsume(nowNanos)) {
            wouldBlockByTokenBucket.increment();
            metrics.recordDryRunBlockedByTokenBucket(targetSystemId);
        }
    }

    void release() {
        inFlight.decrementAndGet();
    }

    void complete(OutboundResult result, Instant now) {
        String targetSystemId = dryRun.targetSystemId();
        OutcomeKind kind = rejudge(result);
        Duration latency = result.latency();

        Duration actualTimeout = base.connection().readTimeout();
        Duration candidateTimeout = candidate.connection().readTimeout();

        if (candidateTimeout.compareTo(actualTimeout) < 0
                && latency.compareTo(candidateTimeout) > 0
                && kind != OutcomeKind.CONNECTION_FAILED) {
            if (result.kind() != OutcomeKind.TIMEOUT) {
                wouldTimeout.increment();
                metrics.recordDryRunTimeout(targetSystemId);
            }
            kind = OutcomeKind.TIMEOUT;
            latency = candidateTimeout;
        } else if (candidateTimeout.compareTo(actualTimeout) > 0 && result.kind() == OutcomeKind.TIMEOUT) {
            unknownTimeouts.increment();
            metrics.recordDryRunUnknownTimeout(targetSystemId);
        }

        outcomes.addLast(new RecordedOutcome(kind, latency, now));
    }

    void recordWaited() {
        waitedByCurrentLimit.increment();
        metrics.recordDryRunWaited(dryRun.targetSystemId());
    }

    synchronized void evaluate(Instant now, long nowNanos, int servers) {
        if (servers > 1) {
            overlap = overlap.plus(Duration.between(lastEvaluatedAt, now));
        }
        removeOlderThan(now.minus(window));

        OutcomeStatistics current = OutcomeStatistics.from(List.copyOf(outcomes));
        Optional<HealthGrade> grade = thresholds.gradeOf(current, candidate.limits().baselineRtt());
        HealthStatus before = status;
        HealthStatus next = grade.isPresent() ? before.next(grade.get(), requiredGoodCount) : before;

        timeInState.merge(before.state(), Duration.between(lastEvaluatedAt, now), Duration::plus);
        lastEvaluatedAt = now;

        if (next.state() != before.state()) {
            transitions++;
            metrics.recordDryRunStateTransition(dryRun.targetSystemId(), before.state(), next.state());
        }

        status = next;
        statistics = current;

        double nextRate = rateRule.next(rate, next.state(), current.sampleCount(), candidate.limits());
        if (nextRate != rate) {
            rate = nextRate;
            concurrencyLimit = candidate.limits().concurrencyLimitAt(nextRate);
            bucket.change(nextRate, candidate.limits().burstCapacityAt(nextRate), nowNanos);
        }
    }

    synchronized DryRunReport report(Instant now, HealthState actualState, double actualRate) {
        Map<HealthState, Duration> times = new EnumMap<>(HealthState.class);
        times.putAll(timeInState);
        times.merge(status.state(), Duration.between(lastEvaluatedAt, now), Duration::plus);

        List<DryRunReport.JudgmentChange> changes = new ArrayList<>();
        judgmentChanges.forEach((key, count) ->
                changes.add(new DryRunReport.JudgmentChange(key.actual(), key.candidate(), count.sum())));

        OutcomeStatistics current = statistics;

        return new DryRunReport(
                observedSince,
                now,
                observedCalls.sum(),
                overlap,
                new DryRunReport.Limits(
                        actualRate,
                        rate,
                        concurrencyLimit,
                        wouldBlockBySemaphore.sum(),
                        wouldBlockByTokenBucket.sum(),
                        waitedByCurrentLimit.sum()),
                changes,
                new DryRunReport.Timeouts(wouldTimeout.sum(), unknownTimeouts.sum()),
                new DryRunReport.Health(
                        actualState,
                        status.state(),
                        transitions,
                        times,
                        current.sampleCount(),
                        current.failureRate(),
                        current.p95()),
                unknowns());
    }

    synchronized Duration overlap() {
        return overlap;
    }

    HealthState state() {
        return status.state();
    }

    double rate() {
        return rate;
    }

    int concurrencyLimit() {
        return concurrencyLimit;
    }

    private OutcomeKind rejudge(OutboundResult result) {
        if (result.statusCode() == null || candidate.response().equals(base.response())) {
            return result.kind();
        }

        OutcomeKind rejudged = judge.judge(candidate.response(), result.statusCode(), result.responseBody());

        if (rejudged != result.kind()) {
            judgmentChanges.computeIfAbsent(new JudgmentKey(result.kind(), rejudged), key -> new LongAdder())
                    .increment();
            metrics.recordDryRunJudgmentChange(dryRun.targetSystemId(), result.kind(), rejudged);
        }

        return rejudged;
    }

    private List<String> unknowns() {
        List<String> unknowns = new ArrayList<>();
        TargetSystemPolicy actualLimits = base.limits();
        TargetSystemPolicy candidateLimits = candidate.limits();

        if (candidateLimits.effectiveRate() > actualLimits.effectiveRate()
                || candidateLimits.concurrencyLimit() > actualLimits.concurrencyLimit()) {
            unknowns.add("새 한도가 지금보다 높아서, 더 보냈을 때 대외기관이 어떻게 응답했을지는 알 수 없습니다");
        }
        if (candidate.connection().readTimeout().compareTo(base.connection().readTimeout()) > 0) {
            unknowns.add("새 read-timeout 이 지금보다 길어서, 지금 timeout 난 호출이 더 기다렸다면 어떻게 됐을지는 알 수 없습니다");
        }
        if (!candidate.connection().connectTimeout().equals(base.connection().connectTimeout())) {
            unknowns.add("connect-timeout 은 연결에 걸린 시간을 따로 재지 않아서 계산하지 않았습니다");
        }
        if (overlap.isPositive()) {
            unknowns.add("다른 서버와 겹친 " + overlap.toSeconds()
                    + "초 동안은 이 서버가 호출의 일부만 봐서, 막혔을 호출이 적게 세어지고 상태 판정 근거가 적었을 수 있습니다");
        }

        return unknowns;
    }

    private void removeOlderThan(Instant threshold) {
        RecordedOutcome oldest = outcomes.peekFirst();

        while (oldest != null && oldest.recordedAt().isBefore(threshold)) {
            outcomes.pollFirst();
            oldest = outcomes.peekFirst();
        }
    }

    private record JudgmentKey(OutcomeKind actual, OutcomeKind candidate) {
    }
}
