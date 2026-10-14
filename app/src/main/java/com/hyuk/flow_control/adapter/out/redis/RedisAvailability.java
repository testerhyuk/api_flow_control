package com.hyuk.flow_control.adapter.out.redis;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.config.SharedLimitProperties;
import com.hyuk.flow_control.domain.limit.LimitStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class RedisAvailability implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(RedisAvailability.class);
    private static final int PHASE = Integer.MIN_VALUE + 1000;

    private final RedisTokenBuckets buckets;
    private final StringRedisTemplate redis;
    private final FlowControlMetrics metrics;
    private final Duration recheckInterval;
    private volatile boolean available;
    private volatile ScheduledExecutorService rechecker;

    public RedisAvailability(RedisTokenBuckets buckets, StringRedisTemplate redis, FlowControlMetrics metrics,
                             MeterRegistry meterRegistry, SharedLimitProperties properties) {
        this.buckets = buckets;
        this.redis = redis;
        this.metrics = metrics;
        this.recheckInterval = properties.recheckInterval();

        Gauge.builder("flow.control.limit.store", () -> available ? 1 : 0).register(meterRegistry);
    }

    public boolean isAvailable() {
        return available;
    }

    public synchronized void markUnavailable(Throwable cause) {
        if (!available) {
            return;
        }

        available = false;
        metrics.recordLimitStoreSwitch(LimitStore.LOCAL);
        log.warn("Redis 를 쓸 수 없어서 서버 안의 토큰 버킷으로 바꿨습니다. {} 마다 다시 확인합니다 ({})",
                recheckInterval, cause == null ? "-" : cause.toString());
    }

    void recheck() {
        if (available) {
            return;
        }

        try {
            buckets.ping();
            redis.execute(connection -> connection.ping(), true);
        } catch (RuntimeException e) {
            metrics.recordRedisError("ping", RedisErrors.isTimeout(e));
            log.debug("Redis 를 아직 쓸 수 없습니다", e);
            return;
        }

        synchronized (this) {
            available = true;
        }
        metrics.recordLimitStoreSwitch(LimitStore.SHARED);
        log.info("Redis 에 연결되어 공용 토큰 버킷을 씁니다");
    }

    @Override
    public synchronized void start() {
        if (rechecker != null) {
            return;
        }

        recheck();
        rechecker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "redis-recheck");
            thread.setDaemon(true);
            return thread;
        });
        rechecker.scheduleWithFixedDelay(this::safeRecheck,
                recheckInterval.toMillis(), recheckInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (rechecker != null) {
            rechecker.shutdownNow();
            rechecker = null;
        }
        buckets.close();
    }

    @Override
    public boolean isRunning() {
        return rechecker != null;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private void safeRecheck() {
        try {
            recheck();
        } catch (RuntimeException e) {
            log.warn("Redis 상태를 확인하지 못했습니다", e);
        }
    }
}
