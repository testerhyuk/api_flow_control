package com.hyuk.flow_control.adapter.out.ratelimit;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.port.out.TargetSystemRateLimiter;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.token.RateLimitResult;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class Bucket4jTargetSystemRateLimiter implements TargetSystemRateLimiter {
    private final Map<String, Bucket> buckets;

    public Bucket4jTargetSystemRateLimiter(TargetSystemPolicyReader policyReader) {
        Map<String, Bucket> created = new HashMap<>();

        policyReader.readAll().forEach((targetSystemId ,policy)->{
            created.put(targetSystemId, createBucket(policy));
        });

        this.buckets = Map.copyOf(created);
    }

    @Override
    public RateLimitResult tryConsume(String targetSystemId) {
        ConsumptionProbe probe = bucketOf(targetSystemId).tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return RateLimitResult.allow();
        }

        return RateLimitResult.reject(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private Bucket createBucket(TargetSystemPolicy policy) {
        return Bucket.builder()
                .addLimit(limit -> limit
                        .capacity(policy.burstCapacity())
                        .refillGreedy(1, policy.refillInterval()))
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
