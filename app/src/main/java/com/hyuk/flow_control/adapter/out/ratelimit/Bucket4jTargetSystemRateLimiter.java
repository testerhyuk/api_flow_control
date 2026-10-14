package com.hyuk.flow_control.adapter.out.ratelimit;

import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.port.out.TargetSystemRateLimiter;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.token.RateLimitResult;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TokensInheritanceStrategy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(0)
public class Bucket4jTargetSystemRateLimiter implements TargetSystemRateLimiter, PolicyChangeListener {
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, TargetSystemPolicy> policies = new ConcurrentHashMap<>();

    public Bucket4jTargetSystemRateLimiter(TargetSystemPolicyReader policyReader) {
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

    private void register(String targetSystemId, TargetSystemPolicy policy) {
        policies.put(targetSystemId, policy);
        buckets.putIfAbsent(targetSystemId, createBucket(policy));
    }

    @Override
    public RateLimitResult tryConsume(String targetSystemId) {
        ConsumptionProbe probe = bucketOf(targetSystemId).tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return RateLimitResult.allow();
        }

        return RateLimitResult.reject(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    @Override
    public void changeRate(String targetSystemId, double ratePerSecond) {
        Bucket bucket = bucketOf(targetSystemId);
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(bandwidthOf(policies.get(targetSystemId), ratePerSecond))
                .build();

        bucket.replaceConfiguration(configuration, TokensInheritanceStrategy.AS_IS);
    }

    private Bucket createBucket(TargetSystemPolicy policy) {
        return Bucket.builder()
                .addLimit(bandwidthOf(policy, policy.effectiveRate()))
                .build();
    }

    private Bandwidth bandwidthOf(TargetSystemPolicy policy, double ratePerSecond) {
        return Bandwidth.builder()
                .capacity(policy.burstCapacityAt(ratePerSecond))
                .refillGreedy(1, policy.refillIntervalAt(ratePerSecond))
                .build();
    }

    private Bucket bucketOf(String targetSystemId) {
        Bucket bucket = buckets.get(targetSystemId);

        if (bucket == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return bucket;
    }
}
