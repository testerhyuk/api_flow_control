package com.hyuk.flow_control.domain.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitResultTest {
    @Test
    @DisplayName("allow 는 통과이고 대기시간이 0 이다")
    void allow() {
        RateLimitResult result = RateLimitResult.allow();

        assertThat(result.allowed()).isTrue();
        assertThat(result.retryAfter()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("reject 는 막힘이고 넘겨준 대기시간을 가진다")
    void reject() {
        RateLimitResult result = RateLimitResult.reject(Duration.ofMillis(10));

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfter()).isEqualTo(Duration.ofMillis(10));
    }

    @Test
    @DisplayName("통과인데 대기시간이 있으면 만들 수 없다")
    void rejectsAllowedWithRetryAfter() {
        assertThatThrownBy(() -> new RateLimitResult(true, Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("대기시간이 없으면 만들 수 없다")
    void rejectsNullRetryAfter() {
        assertThatThrownBy(() -> RateLimitResult.reject(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("대기시간이 음수면 만들 수 없다")
    void rejectsNegativeRetryAfter() {
        assertThatThrownBy(() -> RateLimitResult.reject(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
