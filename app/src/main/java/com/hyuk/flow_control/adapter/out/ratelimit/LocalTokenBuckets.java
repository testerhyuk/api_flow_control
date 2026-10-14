package com.hyuk.flow_control.adapter.out.ratelimit;

import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.token.RateLimitResult;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TokensInheritanceStrategy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LocalTokenBuckets {
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, BucketConfiguration> configurations = new ConcurrentHashMap<>();

    public void register(String targetSystemId, TargetSystemPolicy policy, double ratePerSecond) {
        BucketConfiguration configuration = TokenBucketConfigurations.of(policy, ratePerSecond);

        if (buckets.putIfAbsent(targetSystemId, Bucket.builder().addLimit(configuration.getBandwidths()[0]).build()) == null) {
            configurations.put(targetSystemId, configuration);
        }
    }

    public RateLimitResult tryConsume(String targetSystemId) {
        ConsumptionProbe probe = bucketOf(targetSystemId).tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return RateLimitResult.allow();
        }

        return RateLimitResult.reject(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    public void replaceConfiguration(String targetSystemId, BucketConfiguration configuration) {
        Bucket bucket = bucketOf(targetSystemId);

        if (configuration.equals(configurations.get(targetSystemId))) {
            return;
        }

        bucket.replaceConfiguration(configuration, TokensInheritanceStrategy.AS_IS);
        configurations.put(targetSystemId, configuration);
    }

    private Bucket bucketOf(String targetSystemId) {
        Bucket bucket = buckets.get(targetSystemId);

        if (bucket == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return bucket;
    }
}
