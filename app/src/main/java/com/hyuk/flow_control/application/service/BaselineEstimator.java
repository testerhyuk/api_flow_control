package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.OutcomeStatistics;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
@Order(0)
public class BaselineEstimator implements PolicyChangeListener {
    private static final Logger log = LoggerFactory.getLogger(BaselineEstimator.class);

    public enum Bound {
        LOWER("lower"),
        UPPER("upper");

        private final String label;

        Bound(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Kind {
        MEAN("mean"),
        P95("p95");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public Duration policyValueOf(TargetSystemPolicy limits) {
            return this == MEAN ? limits.baselineRtt() : limits.baselineP95();
        }
    }

    public record Baselines(Duration mean, Duration p95) {
        public Baselines {
            if (mean == null || !mean.isPositive() || p95 == null || !p95.isPositive()) {
                throw new IllegalArgumentException("평소 응답 시간은 0보다 커야 합니다");
            }
        }

        public static Baselines of(TargetSystemPolicy limits) {
            return new Baselines(limits.baselineRtt(), limits.baselineP95());
        }
    }

    private final TargetSystemPolicyReader policyReader;
    private final boolean enabled;
    private final HealthProperties.Baseline settings;
    private final Map<String, Learning> learnings = new ConcurrentHashMap<>();

    public BaselineEstimator(TargetSystemPolicyReader policyReader, HealthProperties healthProperties) {
        this.policyReader = policyReader;
        this.enabled = healthProperties.baselineAuto();
        this.settings = healthProperties.baseline();

        policyReader.readAll().keySet().forEach(targetSystemId -> learnings.put(targetSystemId, new Learning()));
    }

    @Override
    public void onPolicyAdded(ManagedPolicy policy) {
        learnings.putIfAbsent(policy.targetSystemId(), new Learning());
    }

    @Override
    public void onPolicyChanged(ManagedPolicy before, ManagedPolicy after) {
        TargetSystemPolicy was = before.limits();
        TargetSystemPolicy now = after.limits();

        if (!was.baselineRtt().equals(now.baselineRtt()) || !was.baselineP95().equals(now.baselineP95())) {
            learnings.put(after.targetSystemId(), new Learning());
            log.info("정책의 평소 응답 시간이 바뀌어서 배운 값을 버리고 다시 배웁니다 : {} 평균 {}ms -> {}ms, p95 {}ms -> {}ms",
                    after.targetSystemId(), was.baselineRtt().toMillis(), now.baselineRtt().toMillis(),
                    was.baselineP95().toMillis(), now.baselineP95().toMillis());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void observe(String targetSystemId, OutcomeKind kind, Duration latency) {
        if (!enabled || (kind != OutcomeKind.SUCCESS && kind != OutcomeKind.BUSINESS_REJECTED)) {
            return;
        }

        Learning learning = learnings.get(targetSystemId);
        if (learning != null) {
            learning.samples.addLast(new Sample(Instant.now(), latency.toNanos()));
        }
    }

    public void refresh() {
        if (!enabled) {
            return;
        }

        Instant since = Instant.now().minus(settings.window());

        learnings.forEach((targetSystemId, learning) -> {
            try {
                learning.refresh(since, settings.minimumSamples());
                for (Kind kind : Kind.values()) {
                    warnIfClamped(targetSystemId, learning, kind);
                }
            } catch (RuntimeException e) {
                log.warn("baseline 계산 실패 : {}", targetSystemId, e);
            }
        });
    }

    public Baselines effective(String targetSystemId) {
        return effective(targetSystemId, policyReader.read(targetSystemId));
    }

    public Baselines effective(String targetSystemId, TargetSystemPolicy limits) {
        return new Baselines(
                effective(targetSystemId, Kind.MEAN, limits.baselineRtt()),
                effective(targetSystemId, Kind.P95, limits.baselineP95()));
    }

    private Duration effective(String targetSystemId, Kind kind, Duration policyValue) {
        return learned(targetSystemId, kind)
                .map(learned -> clamp(learned, policyValue))
                .orElse(policyValue);
    }

    public Optional<Duration> learned(String targetSystemId, Kind kind) {
        if (!enabled) {
            return Optional.empty();
        }

        Learning learning = learnings.get(targetSystemId);
        return learning == null ? Optional.empty() : Optional.ofNullable(learning.learned.get(kind));
    }

    public int sampleCount(String targetSystemId) {
        Learning learning = learnings.get(targetSystemId);
        return learning == null ? 0 : learning.count;
    }

    public Optional<Bound> clampedBound(String targetSystemId, Kind kind) {
        return learned(targetSystemId, kind)
                .flatMap(learned -> boundOf(learned, kind.policyValueOf(policyReader.read(targetSystemId))));
    }

    private Duration clamp(Duration learned, Duration policyValue) {
        return boundOf(learned, policyValue)
                .map(bound -> limitOf(bound, policyValue))
                .orElse(learned);
    }

    private Optional<Bound> boundOf(Duration learned, Duration policyValue) {
        if (learned.compareTo(limitOf(Bound.LOWER, policyValue)) < 0) {
            return Optional.of(Bound.LOWER);
        }
        if (learned.compareTo(limitOf(Bound.UPPER, policyValue)) > 0) {
            return Optional.of(Bound.UPPER);
        }
        return Optional.empty();
    }

    private Duration limitOf(Bound bound, Duration policyValue) {
        double ratio = bound == Bound.LOWER ? settings.lowerRatio() : settings.upperRatio();
        return Duration.ofNanos(Math.round(policyValue.toNanos() * ratio));
    }

    private void warnIfClamped(String targetSystemId, Learning learning, Kind kind) {
        Optional<Bound> bound = clampedBound(targetSystemId, kind);
        Bound before = learning.warnedBounds.get(kind);
        if (bound.isPresent()) {
            learning.warnedBounds.put(kind, bound.get());
        } else {
            learning.warnedBounds.remove(kind);
        }

        if (bound.isEmpty() || bound.get() == before) {
            return;
        }

        Duration policyValue = kind.policyValueOf(policyReader.read(targetSystemId));
        log.warn("배운 평소 응답 시간({})이 정책 값의 한계에 닿았습니다. 정책 값을 다시 봐야 합니다 : {} 배운 값 {}ms, 정책 값 {}ms, 쓰는 값 {}ms",
                kind.label(), targetSystemId, learning.learned.get(kind).toMillis(), policyValue.toMillis(),
                limitOf(bound.get(), policyValue).toMillis());
    }

    private record Sample(Instant recordedAt, long latencyNanos) {
    }

    private static final class Learning {
        private final Deque<Sample> samples = new ConcurrentLinkedDeque<>();
        private final Map<Kind, Duration> learned = new ConcurrentHashMap<>();
        private final Map<Kind, Bound> warnedBounds = new EnumMap<>(Kind.class);
        private volatile int count;

        private void refresh(Instant since, int minimumSamples) {
            Sample oldest = samples.peekFirst();
            while (oldest != null && oldest.recordedAt().isBefore(since)) {
                samples.pollFirst();
                oldest = samples.peekFirst();
            }

            long[] latencies = samples.stream().mapToLong(Sample::latencyNanos).toArray();
            count = latencies.length;

            if (latencies.length < minimumSamples) {
                learned.clear();
                return;
            }

            Arrays.sort(latencies);
            learned.put(Kind.MEAN, Duration.ofNanos(Math.round(Arrays.stream(latencies).average().orElseThrow())));
            learned.put(Kind.P95, Duration.ofNanos(latencies[OutcomeStatistics.percentile95Index(latencies.length)]));
        }
    }
}
