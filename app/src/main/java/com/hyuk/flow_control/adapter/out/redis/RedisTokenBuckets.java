package com.hyuk.flow_control.adapter.out.redis;

import com.hyuk.flow_control.config.SharedLimitProperties;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.github.bucket4j.distributed.AsyncBucketProxy;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class RedisTokenBuckets {
    private static final String KEY_PREFIX = "flow-control:token-bucket:";
    private static final Duration KEEP_AFTER_FULL = Duration.ofSeconds(1);

    private final LettuceConnectionFactory connectionFactory;
    private final Duration timeout;
    private final Map<String, BucketConfiguration> configurations = new ConcurrentHashMap<>();
    private final Map<String, AsyncBucketProxy> buckets = new ConcurrentHashMap<>();
    private volatile StatefulRedisConnection<byte[], byte[]> connection;
    private volatile ProxyManager<byte[]> proxyManager;

    public RedisTokenBuckets(LettuceConnectionFactory connectionFactory, SharedLimitProperties properties) {
        this.connectionFactory = connectionFactory;
        this.timeout = properties.redisTimeout();
    }

    public void configure(String targetSystemId, BucketConfiguration configuration) {
        configurations.put(targetSystemId, configuration);
    }

    public ConsumptionProbe tryConsume(String targetSystemId) throws TimeoutException {
        return await(bucketOf(targetSystemId).tryConsumeAndReturnRemaining(1));
    }

    public void replaceConfiguration(String targetSystemId, BucketConfiguration configuration) throws TimeoutException {
        configurations.put(targetSystemId, configuration);
        await(bucketOf(targetSystemId).replaceConfiguration(configuration, TokensInheritanceStrategy.AS_IS));
    }

    public void ping() {
        connect().sync().ping();
    }

    public synchronized void close() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    private synchronized StatefulRedisConnection<byte[], byte[]> connect() {
        if (connection != null && connection.isOpen()) {
            return connection;
        }

        connectionFactory.start();
        RedisClient client = (RedisClient) connectionFactory.getRequiredNativeClient();
        StatefulRedisConnection<byte[], byte[]> opened = client.connect(ByteArrayCodec.INSTANCE);
        opened.setTimeout(timeout);

        proxyManager = Bucket4jLettuce.casBasedBuilder(opened)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_FULL))
                .build();
        buckets.clear();
        connection = opened;
        return opened;
    }

    private AsyncBucketProxy bucketOf(String targetSystemId) {
        if (!configurations.containsKey(targetSystemId)) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        ProxyManager<byte[]> manager = proxyManager;
        if (manager == null) {
            throw new IllegalStateException("Redis 에 연결되어 있지 않습니다");
        }

        return buckets.computeIfAbsent(targetSystemId, id -> manager.asAsync().builder().build(
                (KEY_PREFIX + id).getBytes(StandardCharsets.UTF_8),
                () -> CompletableFuture.completedFuture(configurations.get(id))));
    }

    private <T> T await(CompletableFuture<T> future) throws TimeoutException {
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Redis 응답을 기다리다 중단되었습니다", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Redis 호출이 실패했습니다", e.getCause());
        } finally {
            future.cancel(false);
        }
    }
}
