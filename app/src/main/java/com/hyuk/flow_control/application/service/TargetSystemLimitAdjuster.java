package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.SharedRateStore;
import com.hyuk.flow_control.application.port.out.SharedRateStoreUnavailableException;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.port.out.TargetSystemRateLimiter;
import com.hyuk.flow_control.config.ControlProperties;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.config.LimitAdjustmentProperties;
import com.hyuk.flow_control.config.MaintenanceProperties;
import com.hyuk.flow_control.domain.health.HealthMode;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.limit.ControlMode;
import com.hyuk.flow_control.domain.limit.RateAdjustmentRule;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(0)
public class TargetSystemLimitAdjuster implements PolicyChangeListener {
    private static final Logger log = LoggerFactory.getLogger(TargetSystemLimitAdjuster.class);

    private final TargetSystemHealthEvaluator evaluator;
    private final TargetSystemPolicyReader policyReader;
    private final TargetSystemRateLimiter rateLimiter;
    private final TargetSystemSemaphore semaphore;
    private final SharedRateStore sharedRates;
    private final FlowControlMetrics metrics;
    private final BaselineEstimator baselines;
    private final MaintenanceMonitor maintenance;
    private final RateAdjustmentRule rule;
    private final ControlMode controlMode;
    private final double lowTrafficRatio;
    private final double resumeRateRatio;
    private final Map<String, Double> rates = new ConcurrentHashMap<>();
    private final Map<String, Double> appliedRates = new ConcurrentHashMap<>();
    private final Map<String, Integer> servers = new ConcurrentHashMap<>();
    private final Set<String> inherited = ConcurrentHashMap.newKeySet();

    public TargetSystemLimitAdjuster(
            TargetSystemHealthEvaluator evaluator,
            TargetSystemPolicyReader policyReader,
            TargetSystemRateLimiter rateLimiter,
            TargetSystemSemaphore semaphore,
            SharedRateStore sharedRates,
            BaselineEstimator baselines,
            MaintenanceMonitor maintenance,
            FlowControlMetrics metrics,
            LimitAdjustmentProperties adjustmentProperties,
            HealthProperties healthProperties,
            MaintenanceProperties maintenanceProperties,
            ControlProperties controlProperties) {
        this.evaluator = evaluator;
        this.policyReader = policyReader;
        this.rateLimiter = rateLimiter;
        this.semaphore = semaphore;
        this.sharedRates = sharedRates;
        this.baselines = baselines;
        this.maintenance = maintenance;
        this.metrics = metrics;
        this.rule = ruleOf(adjustmentProperties, healthProperties);
        this.controlMode = controlProperties.controlMode();
        this.lowTrafficRatio = healthProperties.lowTraffic().rateRatio();
        this.resumeRateRatio = maintenanceProperties.resumeRateRatio();

        policyReader.readAll().forEach(this::register);

        if (controlMode == ControlMode.ADAPTIVE) {
            log.info("제어 방식 : {}", controlMode.label());
        } else {
            log.warn("제어 방식 : {} (비교 실험용입니다. 운영에서는 adaptive 로 두세요)", controlMode.label());
        }
    }

    public static RateAdjustmentRule ruleOf(LimitAdjustmentProperties adjustmentProperties,
                                            HealthProperties healthProperties) {
        return new RateAdjustmentRule(
                adjustmentProperties.degradedRatio(),
                adjustmentProperties.recoveryStep(),
                healthProperties.minimumCalls(),
                healthProperties.minimumCalls() / (healthProperties.window().toNanos() / 1_000_000_000.0));
    }

    @Override
    public void onPolicyAdded(ManagedPolicy policy) {
        register(policy.targetSystemId(), policy.limits());
    }

    @Override
    public void onPolicyChanged(ManagedPolicy before, ManagedPolicy after) {
    }

    private void register(String targetSystemId, TargetSystemPolicy policy) {
        rates.putIfAbsent(targetSystemId, policy.effectiveRate());
        appliedRates.putIfAbsent(targetSystemId, policy.effectiveRate());
        servers.putIfAbsent(targetSystemId, 1);
    }

    public ControlMode controlMode() {
        return controlMode;
    }

    public void onMaintenanceEnded(String targetSystemId) {
        if (controlMode != ControlMode.ADAPTIVE) {
            return;
        }

        TargetSystemPolicy policy = policyReader.read(targetSystemId);
        double resumed = rule.bounded(Math.min(rateOf(targetSystemId), policy.effectiveRate() * resumeRateRatio), policy);
        rates.put(targetSystemId, resumed);
        log.info("점검이 끝나 rate 를 낮춰 다시 시작합니다 : {} {}", targetSystemId, resumed);
    }

    public void adjustAll() {
        if (controlMode == ControlMode.NONE) {
            return;
        }

        rates.keySet().forEach(targetSystemId -> {
            try {
                adjust(targetSystemId);
            } catch (RuntimeException e) {
                log.warn("대상 시스템 한도 조절 실패 : {}", targetSystemId, e);
            }
        });
    }

    public double rateOf(String targetSystemId) {
        Double rate = rates.get(targetSystemId);

        if (rate == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return rate;
    }

    public double appliedRateOf(String targetSystemId) {
        Double rate = appliedRates.get(targetSystemId);

        if (rate == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return rate;
    }

    public int serversOf(String targetSystemId) {
        return servers.getOrDefault(targetSystemId, 1);
    }

    void adjust(String targetSystemId) {
        TargetSystemPolicy policy = policyReader.read(targetSystemId);
        HealthSnapshot snapshot = evaluator.snapshotOf(targetSystemId);

        double proposed = proposedRate(targetSystemId, policy, snapshot);
        proposed = inheritOnce(targetSystemId, proposed);

        SharedRate shared = share(targetSystemId, proposed);
        servers.put(targetSystemId, shared.servers());

        double before = appliedRateOf(targetSystemId);
        Duration baselineRtt = controlMode == ControlMode.FIXED
                ? policy.baselineRtt()
                : baselines.effective(targetSystemId);
        int concurrency = policy.concurrencyLimitAt(shared.rate(), baselineRtt);

        rateLimiter.changeRate(targetSystemId, shared.rate());
        rates.put(targetSystemId, proposed);

        if (shared.rate() == before && concurrency == semaphore.limitOf(targetSystemId)) {
            return;
        }

        semaphore.changeLimit(targetSystemId, concurrency);
        appliedRates.put(targetSystemId, shared.rate());

        log.info("대상 시스템 한도 변경 : {} {} {} rate {} -> {} (이 서버 {}, 서버 {}대), concurrency {} (baseline {}ms)",
                targetSystemId, snapshot.state(), snapshot.mode(), before, shared.rate(), proposed, shared.servers(),
                concurrency, baselineRtt.toMillis());
    }

    private double proposedRate(String targetSystemId, TargetSystemPolicy policy, HealthSnapshot snapshot) {
        if (controlMode == ControlMode.FIXED) {
            return policy.effectiveRate();
        }
        if (maintenance.isActive(targetSystemId)) {
            return rule.bounded(rateOf(targetSystemId), policy);
        }
        if (snapshot.mode() == HealthMode.LOW_TRAFFIC) {
            return snapshot.state() == HealthState.CRITICAL
                    ? rule.floor(policy)
                    : rule.bounded(policy.effectiveRate() * lowTrafficRatio, policy);
        }
        return rule.next(rateOf(targetSystemId), snapshot.state(), snapshot.sampleCount(), policy);
    }

    private double inheritOnce(String targetSystemId, double proposed) {
        if (inherited.contains(targetSystemId)) {
            return proposed;
        }

        try {
            Optional<Double> stored = sharedRates.appliedRate(targetSystemId);
            inherited.add(targetSystemId);

            return stored.filter(rate -> rate > 0)
                    .map(rate -> {
                        if (rate < proposed) {
                            log.info("공용 버킷에 적용돼 있던 rate 를 이어받습니다 : {} {}", targetSystemId, rate);
                        }
                        return Math.min(proposed, rate);
                    })
                    .orElse(proposed);
        } catch (SharedRateStoreUnavailableException e) {
            recordStoreError(e);
            return proposed;
        }
    }

    private SharedRate share(String targetSystemId, double proposed) {
        try {
            List<Double> live = sharedRates.exchangeProposals(targetSystemId, proposed);
            double lowest = Math.min(proposed, live.stream().mapToDouble(Double::doubleValue).min().orElse(proposed));

            sharedRates.saveAppliedRate(targetSystemId, lowest);
            return new SharedRate(lowest, Math.max(1, live.size()));
        } catch (SharedRateStoreUnavailableException e) {
            recordStoreError(e);
            return new SharedRate(proposed, 1);
        }
    }

    private void recordStoreError(SharedRateStoreUnavailableException e) {
        if (e.isAttempted()) {
            metrics.recordRedisError("proposal", e.isTimeout());
        }
    }

    private record SharedRate(double rate, int servers) {
    }
}
