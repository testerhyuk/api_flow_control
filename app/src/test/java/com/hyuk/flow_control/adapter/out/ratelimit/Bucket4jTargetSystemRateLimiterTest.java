package com.hyuk.flow_control.adapter.out.ratelimit;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.token.RateLimitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class Bucket4jTargetSystemRateLimiterTest {
    private Bucket4jTargetSystemRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        TargetSystemPolicyReader policyReader = mock(TargetSystemPolicyReader.class);
        when(policyReader.readAll()).thenReturn(Map.of(
                "target-a", new TargetSystemPolicy(1, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(3)),
                "target-b", new TargetSystemPolicy(10, Duration.ofSeconds(1), 1.0, Duration.ofMillis(10))
        ));

        rateLimiter = new Bucket4jTargetSystemRateLimiter(policyReader);
    }

    @Test
    @DisplayName("용량만큼 통과한 뒤에는 막힌다")
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
}