package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(0)
public class TargetSystemLimitAdjuster implements PolicyChangeListener {
    private static final Logger log = LoggerFactory.getLogger(TargetSystemLimitAdjuster.class);

    private final TargetSystemHealthEvaluator evaluator;
    private final TargetSystemPolicyReader policyReader;
    private final TargetSystemRateLimiter rateLimiter;
    private final TargetSystemSemaphore semaphore;
    private final RateAdjustmentRule rule;
    private final Map<String, Double> rates = new ConcurrentHashMap<>();
    private final Map<String, TargetSystemPolicy> appliedPolicies = new ConcurrentHashMap<>();

    public TargetSystemLimitAdjuster(
            TargetSystemHealthEvaluator evaluator,
            TargetSystemPolicyReader policyReader,
            TargetSystemRateLimiter rateLimiter,
            TargetSystemSemaphore semaphore,
            LimitAdjustmentProperties adjustmentProperties,
            HealthProperties healthProperties) {
        this.evaluator = evaluator;
        this.policyReader = policyReader;
        this.rateLimiter = rateLimiter;
        this.semaphore = semaphore;
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
        appliedPolicies.putIfAbsent(targetSystemId, policy);
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

    void adjust(String targetSystemId) {
        TargetSystemPolicy policy = policyReader.read(targetSystemId);
        HealthSnapshot snapshot = evaluator.snapshotOf(targetSystemId);

        double current = rateOf(targetSystemId);
        double next = rule.next(current, snapshot.state(), snapshot.sampleCount(), policy);
        boolean policyChanged = !policy.equals(appliedPolicies.get(targetSystemId));

        if (next == current && !policyChanged) {
            return;
        }

        rateLimiter.changeRate(targetSystemId, next);
        semaphore.changeLimit(targetSystemId, policy.concurrencyLimitAt(next));
        rates.put(targetSystemId, next);
        appliedPolicies.put(targetSystemId, policy);

        log.info("대상 시스템 한도 변경 : {} {} rate {} -> {}, concurrency {}",
                targetSystemId, snapshot.state(), current, next, policy.concurrencyLimitAt(next));
    }
}
