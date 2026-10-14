package com.hyuk.flow_control.adapter.out.redis;

import com.hyuk.flow_control.adapter.out.ratelimit.FailoverTargetSystemRateLimiter;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.SharedRateStoreUnavailableException;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.SharedLimitProperties;
import com.hyuk.flow_control.domain.limit.LimitStore;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.support.RedisTestServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SharedRateLimitRedisTest {
    private static final Duration TIMEOUT = Duration.ofMillis(200);
    private static final SharedLimitProperties PROPERTIES =
            new SharedLimitProperties(Duration.ofSeconds(1), TIMEOUT, Duration.ofMillis(200));

    private final List<LettuceConnectionFactory> factories = new ArrayList<>();
    private final List<RedisAvailability> availabilities = new ArrayList<>();
    private String target;

    @BeforeEach
    void setUp() {
        target = "target-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        availabilities.forEach(RedisAvailability::stop);
        factories.forEach(LettuceConnectionFactory::destroy);
    }

    @Test
    @DisplayName("두 서버가 같은 Redis 를 보면, 동시에 꺼내도 합계가 공용 버킷 하나의 한도를 넘지 않는다")
    void twoServersShareOneBucket() {
        Server first = server(RedisTestServer.connectionFactory(TIMEOUT));
        Server second = server(RedisTestServer.connectionFactory(TIMEOUT));

        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            Server server = i % 2 == 0 ? first : second;
            if (server.limiter().tryConsume(target).allowed()) {
                allowed++;
            }
        }

        assertThat(first.availability().isAvailable()).isTrue();
        assertThat(second.availability().isAvailable()).isTrue();
        assertThat(allowed).isBetween(10, 12);
    }

    @Test
    @DisplayName("서버마다 원하는 rate 를 남기면 모든 서버가 서로의 값을 본다")
    void serversSeeEachOthersProposals() {
        RedisSharedRateStore first = store(RedisTestServer.connectionFactory(TIMEOUT));
        RedisSharedRateStore second = store(RedisTestServer.connectionFactory(TIMEOUT));

        assertThat(first.exchangeProposals(target, 95.0)).containsExactly(95.0);
        assertThat(second.exchangeProposals(target, 47.5)).containsExactlyInAnyOrder(95.0, 47.5);
    }

    @Test
    @DisplayName("원하는 rate 를 다시 남기지 않은 서버의 값은 유효 시간이 지나면 사라진다")
    void proposalsExpire() throws InterruptedException {
        RedisSharedRateStore first = store(RedisTestServer.connectionFactory(TIMEOUT));
        RedisSharedRateStore second = store(RedisTestServer.connectionFactory(TIMEOUT));
        second.exchangeProposals(target, 47.5);

        Thread.sleep(1500);

        assertThat(first.exchangeProposals(target, 95.0)).containsExactly(95.0);
    }

    @Test
    @DisplayName("적용된 rate 를 남기고 다른 서버가 읽을 수 있다")
    void savesAppliedRate() {
        RedisSharedRateStore first = store(RedisTestServer.connectionFactory(TIMEOUT));
        RedisSharedRateStore second = store(RedisTestServer.connectionFactory(TIMEOUT));

        assertThat(second.appliedRate(target)).isEmpty();
        first.saveAppliedRate(target, 47.5);

        assertThat(second.appliedRate(target)).contains(47.5);
    }

    @Test
    @DisplayName("Redis 가 멈추면 서버 안 버킷으로 바뀌고, 다시 살아나면 공용 버킷으로 돌아간다")
    void failsOverAndRecovers() throws InterruptedException {
        try (GenericContainer<?> redis = RedisTestServer.newContainer()) {
            redis.start();
            FlowControlMetrics metrics = mock(FlowControlMetrics.class);
            Server server = server(RedisTestServer.connectionFactory(redis, TIMEOUT), metrics);
            RedisSharedRateStore store = new RedisSharedRateStore(
                    new StringRedisTemplate(factories.getLast()), new ServerIdentity(), server.availability(), PROPERTIES);
            assertThat(server.availability().isAvailable()).isTrue();

            redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
            try {
                assertThat(server.limiter().tryConsume(target).allowed()).isTrue();
                assertThat(server.availability().isAvailable()).isFalse();
                verify(metrics).recordLimitStoreSwitch(LimitStore.LOCAL);
                assertThatThrownBy(() -> store.exchangeProposals(target, 95.0))
                        .isInstanceOfSatisfying(SharedRateStoreUnavailableException.class,
                                e -> assertThat(e.isAttempted()).isFalse());
            } finally {
                redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
            }

            await(() -> server.availability().isAvailable());
            verify(metrics, times(2)).recordLimitStoreSwitch(LimitStore.SHARED);
            assertThat(store.exchangeProposals(target, 95.0)).containsExactly(95.0);
        }
    }

    private Server server(LettuceConnectionFactory factory) {
        return server(factory, mock(FlowControlMetrics.class));
    }

    private Server server(LettuceConnectionFactory factory, FlowControlMetrics metrics) {
        factories.add(factory);
        RedisTokenBuckets buckets = new RedisTokenBuckets(factory, PROPERTIES);
        RedisAvailability availability = new RedisAvailability(
                buckets, new StringRedisTemplate(factory), metrics, new SimpleMeterRegistry(), PROPERTIES);
        availabilities.add(availability);

        TargetSystemPolicyReader policyReader = mock(TargetSystemPolicyReader.class);
        when(policyReader.readAll()).thenReturn(Map.of(
                target, new TargetSystemPolicy(10, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(1))));
        FailoverTargetSystemRateLimiter limiter =
                new FailoverTargetSystemRateLimiter(policyReader, buckets, availability, metrics);

        availability.start();
        return new Server(limiter, availability);
    }

    private RedisSharedRateStore store(LettuceConnectionFactory factory) {
        Server server = server(factory);
        return new RedisSharedRateStore(new StringRedisTemplate(factory), new ServerIdentity(), server.availability(),
                PROPERTIES);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("기다린 상태가 되지 않았습니다");
    }

    private record Server(FailoverTargetSystemRateLimiter limiter, RedisAvailability availability) {
    }
}
