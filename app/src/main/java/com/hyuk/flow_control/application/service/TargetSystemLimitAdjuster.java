package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.port.out.TargetSystemRateLimiter;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.config.LimitAdjustmentProperties;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TargetSystemLimitAdjuster {
    private static final Logger log = LoggerFactory.getLogger(TargetSystemLimitAdjuster.class);

    private final TargetSystemHealthEvaluator evaluator;
    private final TargetSystemPolicyReader policyReader;
    private final TargetSystemRateLimiter rateLimiter;
    private final TargetSystemSemaphore semaphore;
    private final LimitAdjustmentProperties adjustmentProperties;
    private final int minimumCalls;
    private final double criticalRate;
    private final Map<String, Double> rates = new ConcurrentHashMap<>();

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
        this.adjustmentProperties = adjustmentProperties;
        this.minimumCalls = healthProperties.minimumCalls();
        this.criticalRate = healthProperties.minimumCalls()
                / (healthProperties.window().toNanos() / 1_000_000_000.0);

        policyReader.readAll().forEach((targetSystemId, policy) ->
                rates.put(targetSystemId, policy.effectiveRate()));
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
        double floor = Math.min(criticalRate, policy.effectiveRate());
        double next = Math.max(floor, nextRate(current, snapshot, policy.effectiveRate(), floor));

        if (next == current) {
            return;
        }

        rateLimiter.changeRate(targetSystemId, next);
        semaphore.changeLimit(targetSystemId, policy.concurrencyLimitAt(next));
        rates.put(targetSystemId, next);

        log.info("대상 시스템 한도 변경 : {} {} rate {} -> {}, concurrency {}",
                targetSystemId, snapshot.state(), current, next, policy.concurrencyLimitAt(next));
    }

    private double nextRate(double current, HealthSnapshot snapshot, double effectiveRate, double floor) {
        return switch (snapshot.state()) {
            case CRITICAL -> floor;
            case DEGRADED -> Math.min(current, effectiveRate * adjustmentProperties.degradedRatio());
            case RECOVERING, HEALTHY -> isGraded(snapshot)
                    ? Math.min(effectiveRate, current + effectiveRate * adjustmentProperties.recoveryStep())
                    : current;
        };
    }

    private boolean isGraded(HealthSnapshot snapshot) {
        return snapshot.sampleCount() >= minimumCalls;
    }
}
