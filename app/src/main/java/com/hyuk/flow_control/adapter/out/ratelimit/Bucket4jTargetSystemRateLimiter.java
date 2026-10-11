package com.hyuk.flow_control.adapter.out.ratelimit;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.port.out.TargetSystemRateLimiter;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.token.RateLimitResult;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TokensInheritanceStrategy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class Bucket4jTargetSystemRateLimiter implements TargetSystemRateLimiter {
    private final Map<String, Bucket> buckets;
    private final Map<String, TargetSystemPolicy> policies;

    public Bucket4jTargetSystemRateLimiter(TargetSystemPolicyReader policyReader) {
        Map<String, Bucket> created = new HashMap<>();

        policyReader.readAll().forEach((targetSystemId, policy) -> {
            created.put(targetSystemId, createBucket(policy));
        });

        this.buckets = Map.copyOf(created);
        this.policies = Map.copyOf(policyReader.readAll());
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
