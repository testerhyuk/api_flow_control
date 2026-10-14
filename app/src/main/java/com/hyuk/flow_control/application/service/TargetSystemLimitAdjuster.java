package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.SharedRateStore;
import com.hyuk.flow_control.application.port.out.SharedRateStoreUnavailableException;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.port.out.TargetSystemRateLimiter;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.config.LimitAdjustmentProperties;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.limit.RateAdjustmentRule;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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
    private final RateAdjustmentRule rule;
    private final Map<String, Double> rates = new ConcurrentHashMap<>();
    private final Map<String, Double> appliedRates = new ConcurrentHashMap<>();
    private final Map<String, Integer> servers = new ConcurrentHashMap<>();
    private final Map<String, TargetSystemPolicy> appliedPolicies = new ConcurrentHashMap<>();
    private final Set<String> inherited = ConcurrentHashMap.newKeySet();

    public TargetSystemLimitAdjuster(
            TargetSystemHealthEvaluator evaluator,
            TargetSystemPolicyReader policyReader,
            TargetSystemRateLimiter rateLimiter,
            TargetSystemSemaphore semaphore,
            SharedRateStore sharedRates,
            FlowControlMetrics metrics,
            LimitAdjustmentProperties adjustmentProperties,
            HealthProperties healthProperties) {
        this.evaluator = evaluator;
        this.policyReader = policyReader;
        this.rateLimiter = rateLimiter;
        this.semaphore = semaphore;
        this.sharedRates = sharedRates;
        this.metrics = metrics;
        this.rule = ruleOf(adjustmentProperties, healthProperties);

        policyReader.readAll().forEach(this::register);
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

    public void adjustAll() {
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

        double proposed = rule.next(rateOf(targetSystemId), snapshot.state(), snapshot.sampleCount(), policy);
        proposed = inheritOnce(targetSystemId, proposed);

        SharedRate shared = share(targetSystemId, proposed);
        servers.put(targetSystemId, shared.servers());

        double before = appliedRateOf(targetSystemId);
        boolean policyChanged = !policy.equals(appliedPolicies.get(targetSystemId));

        rateLimiter.changeRate(targetSystemId, shared.rate());
        rates.put(targetSystemId, proposed);

        if (shared.rate() == before && !policyChanged) {
            return;
        }

        semaphore.changeLimit(targetSystemId, policy.concurrencyLimitAt(shared.rate()));
        appliedRates.put(targetSystemId, shared.rate());
        appliedPolicies.put(targetSystemId, policy);

        log.info("대상 시스템 한도 변경 : {} {} rate {} -> {} (이 서버 {}, 서버 {}대), concurrency {}",
                targetSystemId, snapshot.state(), before, shared.rate(), proposed, shared.servers(),
                policy.concurrencyLimitAt(shared.rate()));
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
