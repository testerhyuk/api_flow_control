package com.hyuk.flow_control.config;

import com.hyuk.flow_control.domain.health.HealthRules;
import com.hyuk.flow_control.domain.health.HealthThresholds;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "flow-control.health")
public record HealthProperties(
        Duration window,
        Duration evaluationInterval,
        int minimumCalls,
        double degradedFailureRate,
        double criticalFailureRate,
        double degradedLatencyMultiplier,
        Duration recoveryDuration,
        boolean antiOscillation,
        RecoveryThreshold recoveryThreshold,
        int degradedStreak,
        LowTraffic lowTraffic,
        boolean baselineAuto,
        Baseline baseline
) {
    @ConstructorBinding
    public HealthProperties(
            Duration window,
            Duration evaluationInterval,
            int minimumCalls,
            double degradedFailureRate,
            double criticalFailureRate,
            double degradedLatencyMultiplier,
            Duration recoveryDuration,
            @DefaultValue("true") boolean antiOscillation,
            RecoveryThreshold recoveryThreshold,
            @DefaultValue("3") int degradedStreak,
            LowTraffic lowTraffic,
            @DefaultValue("true") boolean baselineAuto,
            Baseline baseline) {
        if (window == null || !window.isPositive()) {
            throw new IllegalArgumentException("window 는 0보다 커야 합니다");
        }
        if (evaluationInterval == null || !evaluationInterval.isPositive()) {
            throw new IllegalArgumentException("evaluation-interval 은 0보다 커야 합니다");
        }
        if (minimumCalls <= 0) {
            throw new IllegalArgumentException("minimum-calls 는 0보다 커야 합니다");
        }
        if (degradedFailureRate <= 0 || degradedFailureRate >= criticalFailureRate) {
            throw new IllegalArgumentException("degraded-failure-rate 는 0보다 크고 critical-failure-rate 보다 작아야 합니다");
        }
        if (criticalFailureRate > 1) {
            throw new IllegalArgumentException("critical-failure-rate 는 1 이하여야 합니다");
        }
        if (degradedLatencyMultiplier <= 1) {
            throw new IllegalArgumentException("degraded-latency-multiplier 는 1보다 커야 합니다");
        }
        if (recoveryDuration == null || !recoveryDuration.isPositive()) {
            throw new IllegalArgumentException("recovery-duration 은 0보다 커야 합니다");
        }
        if (recoveryDuration.compareTo(evaluationInterval) <= 0) {
            throw new IllegalArgumentException("recovery-duration 은 evaluation-interval 보다 길어야 합니다");
        }
        if (degradedStreak < 1) {
            throw new IllegalArgumentException("degraded-streak 은 1 이상이어야 합니다");
        }

        recoveryThreshold = recoveryThreshold == null ? RecoveryThreshold.DEFAULT : recoveryThreshold;
        lowTraffic = lowTraffic == null ? LowTraffic.DEFAULT : lowTraffic;
        baseline = baseline == null ? Baseline.DEFAULT : baseline;

        if (recoveryThreshold.degradedFailureRate() > degradedFailureRate) {
            throw new IllegalArgumentException("recovery-threshold.degraded-failure-rate 는 degraded-failure-rate 보다 클 수 없습니다");
        }
        if (recoveryThreshold.criticalFailureRate() > criticalFailureRate) {
            throw new IllegalArgumentException("recovery-threshold.critical-failure-rate 는 critical-failure-rate 보다 클 수 없습니다");
        }
        if (recoveryThreshold.latencyMultiplier() > degradedLatencyMultiplier) {
            throw new IllegalArgumentException("recovery-threshold.latency-multiplier 는 degraded-latency-multiplier 보다 클 수 없습니다");
        }

        this.window = window;
        this.evaluationInterval = evaluationInterval;
        this.minimumCalls = minimumCalls;
        this.degradedFailureRate = degradedFailureRate;
        this.criticalFailureRate = criticalFailureRate;
        this.degradedLatencyMultiplier = degradedLatencyMultiplier;
        this.recoveryDuration = recoveryDuration;
        this.antiOscillation = antiOscillation;
        this.recoveryThreshold = recoveryThreshold;
        this.degradedStreak = degradedStreak;
        this.lowTraffic = lowTraffic;
        this.baselineAuto = baselineAuto;
        this.baseline = baseline;
    }

    public HealthProperties(Duration window, Duration evaluationInterval, int minimumCalls,
                            double degradedFailureRate, double criticalFailureRate,
                            double degradedLatencyMultiplier, Duration recoveryDuration) {
        this(window, evaluationInterval, minimumCalls, degradedFailureRate, criticalFailureRate,
                degradedLatencyMultiplier, recoveryDuration, true, null, 3, null, true, null);
    }

    public HealthThresholds thresholds() {
        return new HealthThresholds(
                minimumCalls,
                degradedFailureRate,
                criticalFailureRate,
                degradedLatencyMultiplier,
                recoveryThreshold.degradedFailureRate(),
                recoveryThreshold.criticalFailureRate(),
                recoveryThreshold.latencyMultiplier());
    }

    public HealthRules rules() {
        return new HealthRules(thresholds(), requiredGoodCount(), degradedStreak, antiOscillation);
    }

    public int requiredGoodCount() {
        return (int) Math.ceil((double) recoveryDuration.toMillis() / evaluationInterval.toMillis());
    }

    public record RecoveryThreshold(
            double degradedFailureRate,
            double criticalFailureRate,
            double latencyMultiplier
    ) {
        static final RecoveryThreshold DEFAULT = new RecoveryThreshold(0.05, 0.25, 1.5);

        public RecoveryThreshold {
            if (degradedFailureRate <= 0) {
                throw new IllegalArgumentException("recovery-threshold.degraded-failure-rate 는 0보다 커야 합니다");
            }
            if (latencyMultiplier <= 1) {
                throw new IllegalArgumentException("recovery-threshold.latency-multiplier 는 1보다 커야 합니다");
            }
        }
    }

    public record LowTraffic(
            int enterAfter,
            double rateRatio,
            int tooManyRequests,
            int consecutiveFailures,
            int consecutiveTimeouts
    ) {
        static final LowTraffic DEFAULT = new LowTraffic(3, 0.5, 1, 3, 3);

        public LowTraffic {
            if (enterAfter < 1) {
                throw new IllegalArgumentException("low-traffic.enter-after 는 1 이상이어야 합니다");
            }
            if (rateRatio <= 0 || rateRatio > 1) {
                throw new IllegalArgumentException("low-traffic.rate-ratio 는 0보다 크고 1 이하여야 합니다");
            }
            if (tooManyRequests < 1 || consecutiveFailures < 1 || consecutiveTimeouts < 1) {
                throw new IllegalArgumentException("low-traffic 의 사건 횟수는 1 이상이어야 합니다");
            }
        }
    }

    public record Baseline(
            Duration window,
            int minimumSamples,
            double lowerRatio,
            double upperRatio
    ) {
        static final Baseline DEFAULT = new Baseline(Duration.ofMinutes(10), 100, 0.5, 1.5);

        public Baseline {
            if (window == null || !window.isPositive()) {
                throw new IllegalArgumentException("baseline.window 는 0보다 커야 합니다");
            }
            if (minimumSamples < 1) {
                throw new IllegalArgumentException("baseline.minimum-samples 는 1 이상이어야 합니다");
            }
            if (lowerRatio <= 0 || lowerRatio > 1) {
                throw new IllegalArgumentException("baseline.lower-ratio 는 0보다 크고 1 이하여야 합니다");
            }
            if (upperRatio < 1) {
                throw new IllegalArgumentException("baseline.upper-ratio 는 1 이상이어야 합니다");
            }
        }
    }
}
