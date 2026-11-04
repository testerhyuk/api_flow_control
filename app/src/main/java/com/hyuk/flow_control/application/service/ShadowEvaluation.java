package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.ResponseJudge;
import com.hyuk.flow_control.domain.dryrun.CountingTokenBucket;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunReport;
import com.hyuk.flow_control.domain.health.HealthRules;
import com.hyuk.flow_control.domain.health.HealthMode;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.HealthStatus;
import com.hyuk.flow_control.domain.health.HealthTracker;
import com.hyuk.flow_control.domain.health.LowTrafficRules;
import com.hyuk.flow_control.domain.health.LowTrafficStreaks;
import com.hyuk.flow_control.domain.health.OutcomeStatistics;
import com.hyuk.flow_control.domain.health.OutcomeWindow;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

class ShadowEvaluation {
    private final DryRun dryRun;
    private final ManagedPolicy base;
    private final ManagedPolicy candidate;
    private final ResponseJudge judge;
    private final RateAdjustmentRule rateRule;
    private final Duration window;
    private final int maxSamples;
    private final FlowControlMetrics metrics;
    private final Instant observedSince;

    private final Deque<RecordedOutcome> outcomes = new ConcurrentLinkedDeque<>();
    private final CountingTokenBucket bucket;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final HealthTracker tracker;
    private final LowTrafficStreaks streaks;
    private volatile long lastBlockedNanos;
    private volatile boolean blockedOnce;

    private final LongAdder observedCalls = new LongAdder();
    private final LongAdder judgedCalls = new LongAdder();
    private final LongAdder wouldBlockBySemaphore = new LongAdder();
    private final LongAdder wouldBlockByTokenBucket = new LongAdder();
    private final LongAdder waitedByCurrentLimit = new LongAdder();
    private final LongAdder wouldTimeout = new LongAdder();
    private final LongAdder unknownTimeouts = new LongAdder();
    private final LongAdder excludedBlocked = new LongAdder();
    private final LongAdder candidateMaintenanceCalls = new LongAdder();
    private final boolean maintenanceChanged;
    private final Map<JudgmentKey, LongAdder> judgmentChanges = new ConcurrentHashMap<>();

    private final Map<HealthState, Duration> timeInState = new EnumMap<>(HealthState.class);
    private volatile OutcomeStatistics statistics = OutcomeStatistics.from(List.of());
    private volatile double rate;
    private volatile int concurrencyLimit;
    private long transitions;
    private Duration overlap = Duration.ZERO;
    private Duration candidateMaintenanceTime = Duration.ZERO;
    private Duration addedMaintenanceTime = Duration.ZERO;
    private Duration removedMaintenanceTime = Duration.ZERO;
    private Instant lastEvaluatedAt;
    private int newSamples;
    private int samplesSinceChange;
    private boolean pausedByMaintenance;

    ShadowEvaluation(DryRun dryRun, ManagedPolicy base, HealthStatus actualStatus, double actualRate,
                     ResponseJudge judge, HealthRules rules, LowTrafficRules lowTraffic, RateAdjustmentRule rateRule,
                     Duration window, int maxSamples, BaselineEstimator.Baselines baselines, FlowControlMetrics metrics,
                     Instant now, long nowNanos) {
        if (base.version() != dryRun.baseVersion()) {
            throw new IllegalArgumentException("dry-run 기준 버전과 지금 정책 버전이 다릅니다");
        }

        this.dryRun = dryRun;
        this.base = base;
        this.candidate = dryRun.candidate();
        this.maintenanceChanged = !candidate.maintenance().equals(base.maintenance());
        this.judge = judge;
        this.tracker = new HealthTracker(rules, maxSamples, lowTraffic, actualStatus);
        this.streaks = new LowTrafficStreaks(lowTraffic);
        this.rateRule = rateRule;
        this.window = window;
        this.maxSamples = maxSamples;
        this.metrics = metrics;
        this.observedSince = now;
        this.lastEvaluatedAt = now;

        TargetSystemPolicy limits = candidate.limits();
        this.rate = Math.min(actualRate, limits.effectiveRate());
        this.concurrencyLimit = limits.concurrencyLimitAt(rate, baselines.mean());
        this.bucket = new CountingTokenBucket(rate, limits.burstCapacityAt(rate), nowNanos);
    }

    DryRun dryRun() {
        return dryRun;
    }

    boolean admit(long nowNanos) {
        String targetSystemId = dryRun.targetSystemId();
        int running = inFlight.incrementAndGet();
        observedCalls.increment();
        metrics.recordDryRunObserved(targetSystemId);

        if (running > concurrencyLimit) {
            wouldBlockBySemaphore.increment();
            metrics.recordDryRunBlockedBySemaphore(targetSystemId);
            markBlocked(nowNanos);
            return false;
        }
        if (!bucket.tryConsume(nowNanos)) {
            wouldBlockByTokenBucket.increment();
            metrics.recordDryRunBlockedByTokenBucket(targetSystemId);
            markBlocked(nowNanos);
            return false;
        }
        return true;
    }

    private void markBlocked(long nowNanos) {
        lastBlockedNanos = nowNanos;
        blockedOnce = true;
    }

    private boolean blockedWithin(long nowNanos) {
        return blockedOnce && nowNanos - lastBlockedNanos <= window.toNanos();
    }

    void release() {
        inFlight.decrementAndGet();
    }

    void complete(OutboundResult result, Instant now) {
        complete(result, now, true);
    }

    void complete(OutboundResult result, Instant now, boolean admitted) {
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

        if (!admitted) {
            excludedBlocked.increment();
            return;
        }
        if (maintenanceChanged && candidate.maintenance().isActiveAt(now)) {
            candidateMaintenanceCalls.increment();
            return;
        }

        outcomes.addLast(new RecordedOutcome(kind, latency, now));
        streaks.track(kind);
        if (OutcomeStatistics.isSample(kind)) {
            countSample();
        }
    }

    void recordWaited() {
        waitedByCurrentLimit.increment();
        metrics.recordDryRunWaited(dryRun.targetSystemId());
    }

    synchronized void evaluate(Instant now, long nowNanos, int servers, BaselineEstimator.Baselines baselines) {
        evaluate(now, nowNanos, servers, baselines, false);
    }

    synchronized void evaluate(Instant now, long nowNanos, int servers, BaselineEstimator.Baselines baselines,
                               boolean underMaintenance) {
        Duration elapsed = Duration.between(lastEvaluatedAt, now);
        if (servers > 1) {
            overlap = overlap.plus(elapsed);
        }
        boolean candidateMaintenance = maintenanceChanged && candidate.maintenance().isActiveAt(now);
        if (maintenanceChanged) {
            countMaintenanceTime(now, elapsed, candidateMaintenance);
        }
        Instant since = now.minus(window);
        removeOlderThan(since);

        int samples = drainNewSamples();
        HealthStatus before = tracker.status();
        if (underMaintenance || candidateMaintenance) {
            streaks.drain();
            tracker.pause();
            pausedByMaintenance = true;
            timeInState.merge(before.state(), elapsed, Duration::plus);
            lastEvaluatedAt = now;
            return;
        }
        if (pausedByMaintenance) {
            pausedByMaintenance = false;
            restartAfterMaintenance(before, nowNanos);
        }

        OutcomeStatistics current = OutcomeWindow.latest(outcomes.descendingIterator(), maxSamples, since).statistics();
        HealthTracker.Step step = tracker.judge(current, baselines.p95(), streaks.drain(), samples,
                () -> blockedWithin(nowNanos));
        HealthStatus next = step.next();

        timeInState.merge(before.state(), elapsed, Duration::plus);
        lastEvaluatedAt = now;

        if (step.stateChanged()) {
            transitions++;
            metrics.recordDryRunStateTransition(dryRun.targetSystemId(), step.before().state(), next.state());
        }

        statistics = current;

        samplesSinceChange += step.samples();
        double nextRate = rateRule.next(rate, step.mode(), next.state(), step.grade(), samplesSinceChange,
                candidate.limits());
        int nextConcurrency = candidate.limits().concurrencyLimitAt(nextRate, baselines.mean());
        if (nextRate != rate) {
            bucket.change(nextRate, candidate.limits().burstCapacityAt(nextRate), nowNanos);
            samplesSinceChange = 0;
        }
        rate = nextRate;
        concurrencyLimit = nextConcurrency;
    }

    private void restartAfterMaintenance(HealthStatus before, long nowNanos) {
        tracker.restartRecovering();
        if (before.state() != tracker.status().state()) {
            transitions++;
            metrics.recordDryRunStateTransition(dryRun.targetSystemId(), before.state(), tracker.status().state());
        }

        double floor = rateRule.floor(candidate.limits());
        bucket.change(floor, candidate.limits().burstCapacityAt(floor), nowNanos);
        rate = floor;
        samplesSinceChange = 0;
    }

    private void countMaintenanceTime(Instant now, Duration elapsed, boolean candidateMaintenance) {
        boolean baseMaintenance = base.maintenance().isActiveAt(now);

        if (candidateMaintenance) {
            candidateMaintenanceTime = candidateMaintenanceTime.plus(elapsed);
        }
        if (candidateMaintenance && !baseMaintenance) {
            addedMaintenanceTime = addedMaintenanceTime.plus(elapsed);
        }
        if (!candidateMaintenance && baseMaintenance) {
            removedMaintenanceTime = removedMaintenanceTime.plus(elapsed);
        }
    }

    private synchronized void countSample() {
        judgedCalls.increment();
        newSamples++;
    }

    private int drainNewSamples() {
        int drained = newSamples;
        newSamples = 0;
        return drained;
    }

    synchronized DryRunReport report(Instant now, HealthState actualState, double actualRate) {
        Map<HealthState, Duration> times = new EnumMap<>(HealthState.class);
        times.putAll(timeInState);
        times.merge(tracker.status().state(), Duration.between(lastEvaluatedAt, now), Duration::plus);

        List<DryRunReport.JudgmentChange> changes = new ArrayList<>();
        judgmentChanges.forEach((key, count) ->
                changes.add(new DryRunReport.JudgmentChange(key.actual(), key.candidate(), count.sum())));

        OutcomeStatistics current = statistics;

        return new DryRunReport(
                observedSince,
                now,
                observedCalls.sum(),
                judgedCalls.sum(),
                maxSamples,
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
                        tracker.status().state(),
                        transitions,
                        times,
                        current.sampleCount(),
                        current.failureRate(),
                        current.p95()),
                maintenanceChanged
                        ? new DryRunReport.Maintenance(candidateMaintenanceTime, candidateMaintenanceCalls.sum(),
                        addedMaintenanceTime, removedMaintenanceTime)
                        : null,
                unknowns());
    }

    synchronized Duration overlap() {
        return overlap;
    }

    HealthState state() {
        return tracker.status().state();
    }

    HealthMode mode() {
        return tracker.mode();
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
            unknowns.add("새 한도가 지금보다 높아서, 더 보냈을 때 외부 서버이 어떻게 응답했을지는 알 수 없습니다");
        }
        long blocked = excludedBlocked.sum();
        if (blocked > 0) {
            unknowns.add("새 한도였다면 막혔을 호출 " + blocked
                    + "건은 상태 판정에서 뺐습니다. 한도를 낮추면 외부 서버의 부담이 줄어 실패율과 응답 시간이 나아졌을 수 있지만, 얼마나 나아졌을지는 알 수 없습니다");
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
        if (maintenanceChanged) {
            unknowns.add("새 점검 일정이었다면 점검 중에 요청을 읽지 않아 쌓였을 요청이, 점검이 끝난 뒤 어떻게 나갔을지는 알 수 없습니다");
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
