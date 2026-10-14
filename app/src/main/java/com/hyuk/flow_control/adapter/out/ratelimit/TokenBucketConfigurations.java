package com.hyuk.flow_control.adapter.out.ratelimit;

import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;

public final class TokenBucketConfigurations {
    private TokenBucketConfigurations() {
    }

    public static BucketConfiguration of(TargetSystemPolicy policy, double ratePerSecond) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(policy.burstCapacityAt(ratePerSecond))
                        .refillGreedy(1, policy.refillIntervalAt(ratePerSecond))
                        .build())
                .build();
    }
}
