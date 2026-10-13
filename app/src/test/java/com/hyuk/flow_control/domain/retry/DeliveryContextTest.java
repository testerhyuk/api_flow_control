package com.hyuk.flow_control.domain.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryContextTest {
    private static final Instant T1 = Instant.parse("2026-10-13T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-13T09:00:05Z");

    @Test
    @DisplayName("처음 들어온 요청은 단계와 실패 기록이 없다")
    void first() {
        DeliveryContext context = DeliveryContext.first("topic-0@1");

        assertThat(context).isEqualTo(new DeliveryContext(null, 0, null, null, "topic-0@1"));
        assertThat(context.isRetry()).isFalse();
    }

    @Test
    @DisplayName("실패하면 실패 횟수가 늘고 처음 실패 시각은 처음 한 번만 정해진다")
    void afterFailure() {
        DeliveryContext once = DeliveryContext.first("o").afterFailure(T1);
        DeliveryContext twice = once.afterFailure(T2);

        assertThat(once.failedAttempts()).isEqualTo(1);
        assertThat(once.firstFailedAt()).isEqualTo(T1);
        assertThat(twice.failedAttempts()).isEqualTo(2);
        assertThat(twice.firstFailedAt()).isEqualTo(T1);
        assertThat(twice.lastFailedAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("다음 단계로 옮겨도 실패 기록과 처음 들어온 위치는 그대로다")
    void movedTo() {
        DeliveryContext moved = DeliveryContext.first("o").afterFailure(T1).movedTo(RetryStage.SECOND);

        assertThat(moved.stage()).isEqualTo(RetryStage.SECOND);
        assertThat(moved.isRetry()).isTrue();
        assertThat(moved.failedAttempts()).isEqualTo(1);
        assertThat(moved.firstFailedAt()).isEqualTo(T1);
        assertThat(moved.origin()).isEqualTo("o");
    }

    @Test
    @DisplayName("실패 횟수가 음수이거나 처음 들어온 위치가 없으면 만들 수 없다")
    void validation() {
        assertThatThrownBy(() -> new DeliveryContext(null, -1, null, null, "o"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryContext(null, 0, null, null, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
