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
}