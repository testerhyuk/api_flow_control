package com.hyuk.flow_control.adapter.out.ratelimit;

import com.hyuk.flow_control.adapter.out.redis.RedisAvailability;
import com.hyuk.flow_control.adapter.out.redis.RedisErrors;
import com.hyuk.flow_control.adapter.out.redis.RedisTokenBuckets;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.port.out.TargetSystemRateLimiter;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.token.RateLimitResult;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(0)
public class FailoverTargetSystemRateLimiter implements TargetSystemRateLimiter, PolicyChangeListener {
    private final LocalTokenBuckets local = new LocalTokenBuckets();
    private final RedisTokenBuckets shared;
    private final RedisAvailability availability;
    private final FlowControlMetrics metrics;
    private final Map<String, TargetSystemPolicy> policies = new ConcurrentHashMap<>();
    private final Map<String, Double> rates = new ConcurrentHashMap<>();
    private final Map<String, BucketConfiguration> writtenToShared = new ConcurrentHashMap<>();

    public FailoverTargetSystemRateLimiter(TargetSystemPolicyReader policyReader,
                                           RedisTokenBuckets shared,
                                           RedisAvailability availability,
                                           FlowControlMetrics metrics) {
        this.shared = shared;
        this.availability = availability;
        this.metrics = metrics;

        policyReader.readAll().forEach(this::register);
    }

    @Override
    public void onPolicyAdded(ManagedPolicy policy) {
        register(policy.targetSystemId(), policy.limits());
    }

    @Override
    public void onPolicyChanged(ManagedPolicy before, ManagedPolicy after) {
        policies.put(after.targetSystemId(), after.limits());
    }

    @Override
    public RateLimitResult tryConsume(String targetSystemId) {
        if (availability.isAvailable()) {
            long startedAt = System.nanoTime();
            try {
                ConsumptionProbe probe = shared.tryConsume(targetSystemId);
                metrics.recordRedisLatency(Duration.ofNanos(System.nanoTime() - startedAt));
                return toResult(probe);
            } catch (Exception e) {
                failOver("token", e);
            }
        }

        return local.tryConsume(targetSystemId);
    }

    @Override
    public void changeRate(String targetSystemId, double ratePerSecond) {
        TargetSystemPolicy policy = policyOf(targetSystemId);
        BucketConfiguration configuration = TokenBucketConfigurations.of(policy, ratePerSecond);

        rates.put(targetSystemId, ratePerSecond);
        local.replaceConfiguration(targetSystemId, configuration);
        shared.configure(targetSystemId, configuration);

        if (!availability.isAvailable() || configuration.equals(writtenToShared.get(targetSystemId))) {
            return;
        }

        try {
            shared.replaceConfiguration(targetSystemId, configuration);
            writtenToShared.put(targetSystemId, configuration);
        } catch (Exception e) {
            failOver("configuration", e);
        }
    }

    private void register(String targetSystemId, TargetSystemPolicy policy) {
        policies.put(targetSystemId, policy);
        double rate = rates.computeIfAbsent(targetSystemId, id -> policy.effectiveRate());

        local.register(targetSystemId, policy, rate);
        shared.configure(targetSystemId, TokenBucketConfigurations.of(policy, rate));
    }

    private void failOver(String operation, Exception e) {
        metrics.recordRedisError(operation, RedisErrors.isTimeout(e));
        writtenToShared.clear();
        availability.markUnavailable(e);
    }

    private TargetSystemPolicy policyOf(String targetSystemId) {
        TargetSystemPolicy policy = policies.get(targetSystemId);

        if (policy == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return policy;
    }

    private static RateLimitResult toResult(ConsumptionProbe probe) {
        if (probe.isConsumed()) {
            return RateLimitResult.allow();
        }

        return RateLimitResult.reject(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }
}
