package com.hyuk.mock_target.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerSecondLimiterTest {
    private final AtomicLong now = new AtomicLong(10_000);
    private final PerSecondLimiter limiter = new PerSecondLimiter(2, now::get);

    @Test
    @DisplayName("1초 안에서는 한도만큼만 받고 넘으면 거절한다")
    void rejectsOverLimitWithinSecond() {
        assertThat(limiter.tryAcquire()).isTrue();
        now.addAndGet(300);
        assertThat(limiter.tryAcquire()).isTrue();
        now.addAndGet(300);
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("다음 1초가 시작되면 다시 받는다")
    void resetsOnNextSecond() {
        limiter.tryAcquire();
        limiter.tryAcquire();
        now.set(10_999);
        assertThat(limiter.tryAcquire()).isFalse();

        now.set(11_000);
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("한도가 0 이하면 만들 수 없다")
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new PerSecondLimiter(0, now::get))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
