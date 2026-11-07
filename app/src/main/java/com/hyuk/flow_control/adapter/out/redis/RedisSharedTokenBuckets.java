package com.hyuk.flow_control.adapter.out.redis;

import com.hyuk.flow_control.adapter.out.SharedTokenBuckets;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeoutException;

/** Redis 에 둔 토큰 버킷을 공용 버킷으로 내준다. */
@Component
public class RedisSharedTokenBuckets implements SharedTokenBuckets {
    private final RedisTokenBuckets buckets;
    private final RedisAvailability availability;

    public RedisSharedTokenBuckets(RedisTokenBuckets buckets, RedisAvailability availability) {
        this.buckets = buckets;
        this.availability = availability;
    }

    @Override
    public boolean isAvailable() {
        return availability.isAvailable();
    }

    @Override
    public void configure(String targetSystemId, BucketConfiguration configuration) {
        buckets.configure(targetSystemId, configuration);
    }

    @Override
    public ConsumptionProbe tryConsume(String targetSystemId) throws TimeoutException {
        return buckets.tryConsume(targetSystemId);
    }

    @Override
    public void replaceConfiguration(String targetSystemId, BucketConfiguration configuration) throws TimeoutException {
        buckets.replaceConfiguration(targetSystemId, configuration);
    }

    @Override
    public void markUnavailable(Throwable cause) {
        availability.markUnavailable(cause);
    }

    @Override
    public boolean isTimeout(Throwable error) {
        return RedisErrors.isTimeout(error);
    }
}
