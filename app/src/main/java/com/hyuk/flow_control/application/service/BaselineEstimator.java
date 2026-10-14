package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Deque;
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
        if (!before.limits().baselineRtt().equals(after.limits().baselineRtt())) {
            learnings.put(after.targetSystemId(), new Learning());
            log.info("정책의 baseline-rtt 가 바뀌어서 배운 값을 버리고 다시 배웁니다 : {} {}ms -> {}ms",
                    after.targetSystemId(), before.limits().baselineRtt().toMillis(),
                    after.limits().baselineRtt().toMillis());
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
                warnIfClamped(targetSystemId, learning);
            } catch (RuntimeException e) {
                log.warn("baseline 계산 실패 : {}", targetSystemId, e);
            }
        });
    }

    public Duration effective(String targetSystemId) {
        return effective(targetSystemId, policyReader.read(targetSystemId).baselineRtt());
    }

    public Duration effective(String targetSystemId, Duration policyBaseline) {
        return learned(targetSystemId)
                .map(learned -> clamp(learned, policyBaseline))
                .orElse(policyBaseline);
    }

    public Optional<Duration> learned(String targetSystemId) {
        if (!enabled) {
            return Optional.empty();
        }

        Learning learning = learnings.get(targetSystemId);
        return learning == null ? Optional.empty() : Optional.ofNullable(learning.median);
    }

    public int sampleCount(String targetSystemId) {
        Learning learning = learnings.get(targetSystemId);
        return learning == null ? 0 : learning.count;
    }

    public Optional<Bound> clampedBound(String targetSystemId) {
        return learned(targetSystemId)
                .flatMap(learned -> boundOf(learned, policyReader.read(targetSystemId).baselineRtt()));
    }

    private Duration clamp(Duration learned, Duration policyBaseline) {
        return boundOf(learned, policyBaseline)
                .map(bound -> limitOf(bound, policyBaseline))
                .orElse(learned);
    }

    private Optional<Bound> boundOf(Duration learned, Duration policyBaseline) {
        if (learned.compareTo(limitOf(Bound.LOWER, policyBaseline)) < 0) {
            return Optional.of(Bound.LOWER);
        }
        if (learned.compareTo(limitOf(Bound.UPPER, policyBaseline)) > 0) {
            return Optional.of(Bound.UPPER);
        }
        return Optional.empty();
    }

    private Duration limitOf(Bound bound, Duration policyBaseline) {
        double ratio = bound == Bound.LOWER ? settings.lowerRatio() : settings.upperRatio();
        return Duration.ofNanos(Math.round(policyBaseline.toNanos() * ratio));
    }

    private void warnIfClamped(String targetSystemId, Learning learning) {
        Optional<Bound> bound = clampedBound(targetSystemId);
        Bound before = learning.warnedBound;
        learning.warnedBound = bound.orElse(null);

        if (bound.isEmpty() || bound.get() == before) {
            return;
        }

        Duration policyBaseline = policyReader.read(targetSystemId).baselineRtt();
        log.warn("배운 baseline-rtt 가 정책 값의 한계에 닿았습니다. 정책의 baseline-rtt 를 다시 봐야 합니다 : {} 배운 값 {}ms, 정책 값 {}ms, 쓰는 값 {}ms",
                targetSystemId, learning.median.toMillis(), policyBaseline.toMillis(),
                limitOf(bound.get(), policyBaseline).toMillis());
    }

    private record Sample(Instant recordedAt, long latencyNanos) {
    }

    private static final class Learning {
        private final Deque<Sample> samples = new ConcurrentLinkedDeque<>();
        private volatile Duration median;
        private volatile int count;
        private Bound warnedBound;

        private void refresh(Instant since, int minimumSamples) {
            Sample oldest = samples.peekFirst();
            while (oldest != null && oldest.recordedAt().isBefore(since)) {
                samples.pollFirst();
                oldest = samples.peekFirst();
            }

            long[] latencies = samples.stream().mapToLong(Sample::latencyNanos).toArray();
            count = latencies.length;

            if (latencies.length < minimumSamples) {
                median = null;
                return;
            }

            Arrays.sort(latencies);
            median = Duration.ofNanos(latencies[(latencies.length - 1) / 2]);
        }
    }
}
