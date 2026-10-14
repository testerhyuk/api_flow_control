package com.hyuk.flow_control.adapter.out.ratelimit;

import com.hyuk.flow_control.adapter.out.redis.RedisAvailability;
import com.hyuk.flow_control.adapter.out.redis.RedisTokenBuckets;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.token.RateLimitResult;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FailoverTargetSystemRateLimiterTest {
    private FailoverTargetSystemRateLimiter rateLimiter;
    private RedisTokenBuckets shared;
    private RedisAvailability availability;
    private FlowControlMetrics metrics;

    @BeforeEach
    void setUp() {
        shared = mock(RedisTokenBuckets.class);
        availability = mock(RedisAvailability.class);
        metrics = mock(FlowControlMetrics.class);
        TargetSystemPolicyReader policyReader = mock(TargetSystemPolicyReader.class);
        when(policyReader.readAll()).thenReturn(Map.of(
                "target-a", new TargetSystemPolicy(1, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(3)),
                "target-b", new TargetSystemPolicy(10, Duration.ofSeconds(1), 1.0, Duration.ofMillis(10))
        ));

        rateLimiter = new FailoverTargetSystemRateLimiter(policyReader, shared, availability, metrics);
    }

    @Test
    @DisplayName("Redis 를 쓸 수 없으면 서버 안 버킷으로 용량만큼 통과한 뒤에는 막힌다")
    void allowsUpToCapacity() {
        assertThat(rateLimiter.tryConsume("target-a").allowed()).isTrue();
        assertThat(rateLimiter.tryConsume("target-a").allowed()).isTrue();
        assertThat(rateLimiter.tryConsume("target-a").allowed()).isTrue();
        assertThat(rateLimiter.tryConsume("target-a").allowed()).isFalse();
    }

    @Test
    @DisplayName("막히면 다음 토큰까지 남은 시간을 알려 준다")
    void returnsRetryAfterWhenRejected() {
        rateLimiter.tryConsume("target-b");

        RateLimitResult result = rateLimiter.tryConsume("target-b");

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfter())
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("충전 간격이 지나면 다시 통과한다")
    void allowsAgainAfterRefill() throws InterruptedException {
        rateLimiter.tryConsume("target-b");
        RateLimitResult rejected = rateLimiter.tryConsume("target-b");

        Thread.sleep(rejected.retryAfter().toMillis() + 20);

        assertThat(rateLimiter.tryConsume("target-b").allowed()).isTrue();
    }

    @Test
    @DisplayName("한 대상의 토큰이 떨어져도 다른 대상은 통과한다")
    void targetsAreIndependent() {
        rateLimiter.tryConsume("target-b");
        assertThat(rateLimiter.tryConsume("target-b").allowed()).isFalse();

        assertThat(rateLimiter.tryConsume("target-a").allowed()).isTrue();
    }

    @Test
    @DisplayName("설정에 없는 대상이면 예외가 난다")
    void rejectsUnknownTarget() {
        assertThatThrownBy(() -> rateLimiter.tryConsume("target-z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("통과하면 대기시간은 0 이다")
    void retryAfterIsZeroWhenAllowed() {
        RateLimitResult result = rateLimiter.tryConsume("target-a");

        assertThat(result.allowed()).isTrue();
        assertThat(result.retryAfter()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("오래 기다려도 토큰은 용량까지만 쌓인다")
    void doesNotAccumulateBeyondCapacity() throws InterruptedException {
        rateLimiter.tryConsume("target-b");

        Thread.sleep(350);

        assertThat(rateLimiter.tryConsume("target-b").allowed()).isTrue();
        assertThat(rateLimiter.tryConsume("target-b").allowed()).isFalse();
    }

    @Test
    @DisplayName("rate 를 줄이면 다음 토큰까지 더 오래 기다린다")
    void waitsLongerAfterRateDecrease() {
        rateLimiter.changeRate("target-b", 1.0);

        assertThat(rateLimiter.tryConsume("target-b").allowed()).isTrue();
        RateLimitResult rejected = rateLimiter.tryConsume("target-b");

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter())
                .isGreaterThan(Duration.ofMillis(500))
                .isLessThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("rate 를 바꿔도 남아 있던 토큰은 그대로이고 새로 채워 주지 않는다")
    void keepsRemainingTokensOnChange() {
        rateLimiter.changeRate("target-a", 2.0);

        assertThat(rateLimiter.tryConsume("target-a").allowed()).isTrue();
        assertThat(rateLimiter.tryConsume("target-a").allowed()).isTrue();
        assertThat(rateLimiter.tryConsume("target-a").allowed()).isTrue();
        assertThat(rateLimiter.tryConsume("target-a").allowed()).isFalse();
    }

    @Test
    @DisplayName("rate 를 늘리면 늘어난 속도로 다시 채워진다")
    void refillsFasterAfterRateIncrease() {
        rateLimiter.tryConsume("target-a");
        rateLimiter.tryConsume("target-a");
        rateLimiter.tryConsume("target-a");

        rateLimiter.changeRate("target-a", 10.0);
        RateLimitResult rejected = rateLimiter.tryConsume("target-a");

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isLessThanOrEqualTo(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("한 대상의 rate 를 바꿔도 다른 대상은 그대로다")
    void changeRateIsPerTarget() {
        rateLimiter.changeRate("target-a", 0.1);

        rateLimiter.tryConsume("target-b");
        RateLimitResult rejected = rateLimiter.tryConsume("target-b");

        assertThat(rejected.retryAfter()).isLessThanOrEqualTo(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("설정에 없는 대상이나 0 이하 rate 로는 바꿀 수 없다")
    void rejectsInvalidChange() {
        assertThatThrownBy(() -> rateLimiter.changeRate("target-z", 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rateLimiter.changeRate("target-a", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Redis 를 쓸 수 있으면 공용 버킷에서 토큰을 꺼내고 왕복 시간을 남긴다")
    void usesSharedBucketWhenAvailable() throws TimeoutException {
        when(availability.isAvailable()).thenReturn(true);
        when(shared.tryConsume("target-a")).thenReturn(ConsumptionProbe.rejected(0, 2_000_000L, 2_000_000L));

        RateLimitResult result = rateLimiter.tryConsume("target-a");

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfter()).isEqualTo(Duration.ofMillis(2));
        verify(metrics).recordRedisLatency(any());
    }

    @Test
    @DisplayName("공용 버킷에서 실패하면 바로 서버 안 버킷으로 바꾸고, 그 호출도 서버 안 버킷으로 처리한다")
    void failsOverToLocalBucket() throws TimeoutException {
        when(availability.isAvailable()).thenReturn(true);
        when(shared.tryConsume("target-a")).thenThrow(new TimeoutException("slow"));

        RateLimitResult result = rateLimiter.tryConsume("target-a");

        assertThat(result.allowed()).isTrue();
        verify(metrics).recordRedisError("token", true);
        verify(availability).markUnavailable(any(TimeoutException.class));
    }

    @Test
    @DisplayName("rate 를 바꾸면 서버 안 버킷과 공용 버킷 설정을 같이 바꾸되, 같은 설정은 Redis 에 다시 쓰지 않는다")
    void writesSharedConfigurationOnlyWhenChanged() throws TimeoutException {
        when(availability.isAvailable()).thenReturn(true);

        rateLimiter.changeRate("target-b", 5.0);
        rateLimiter.changeRate("target-b", 5.0);
        rateLimiter.changeRate("target-b", 4.0);

        verify(shared, times(2)).replaceConfiguration(eq("target-b"), any(BucketConfiguration.class));
    }

    @Test
    @DisplayName("Redis 를 쓸 수 없는 동안은 공용 버킷 설정을 쓰지 않고, 다시 쓸 수 있게 되면 다음 변경 때 다시 쓴다")
    void rewritesSharedConfigurationAfterRecovery() throws TimeoutException {
        when(availability.isAvailable()).thenReturn(true);
        rateLimiter.changeRate("target-b", 5.0);
        doThrow(new TimeoutException("slow")).when(shared).tryConsume("target-b");
        rateLimiter.tryConsume("target-b");

        when(availability.isAvailable()).thenReturn(false);
        rateLimiter.changeRate("target-b", 5.0);
        when(availability.isAvailable()).thenReturn(true);
        rateLimiter.changeRate("target-b", 5.0);

        verify(shared, times(2)).replaceConfiguration(eq("target-b"), any(BucketConfiguration.class));
    }

    @Test
    @DisplayName("공용 버킷 설정을 쓰다 실패해도 서버 안 버킷으로 바꾼다")
    void failsOverWhenConfigurationFails() throws TimeoutException {
        when(availability.isAvailable()).thenReturn(true);
        doThrow(new IllegalStateException("down")).when(shared).replaceConfiguration(eq("target-b"), any());

        rateLimiter.changeRate("target-b", 5.0);

        verify(metrics).recordRedisError(eq("configuration"), anyBoolean());
        verify(availability).markUnavailable(any());
    }
}
