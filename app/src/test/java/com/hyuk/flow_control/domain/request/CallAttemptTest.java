package com.hyuk.flow_control.domain.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallAttemptTest {
    @Test
    @DisplayName("Called 는 넘겨준 외부 응답을 가진다")
    void calledKeepsResult() {
        OutboundResult result = new OutboundResult(OutcomeKind.SUCCESS, Duration.ofMillis(120), null, null);

        assertThat(new CallAttempt.Called(result).result()).isEqualTo(result);
    }

    @Test
    @DisplayName("Called 는 외부 응답 없이 만들 수 없다")
    void calledRejectsNullResult() {
        assertThatThrownBy(() -> new CallAttempt.Called(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BlockedByTokenBucket 은 넘겨준 대기시간을 가진다")
    void blockedByTokenBucketKeepsRetryAfter() {
        assertThat(new CallAttempt.BlockedByTokenBucket(Duration.ofMillis(10)).retryAfter())
                .isEqualTo(Duration.ofMillis(10));
        assertThat(new CallAttempt.BlockedByTokenBucket(Duration.ZERO).retryAfter())
                .isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("BlockedByTokenBucket 은 대기시간이 없으면 만들 수 없다")
    void blockedByTokenBucketRejectsNullRetryAfter() {
        assertThatThrownBy(() -> new CallAttempt.BlockedByTokenBucket(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BlockedByTokenBucket 은 대기시간이 음수면 만들 수 없다")
    void blockedByTokenBucketRejectsNegativeRetryAfter() {
        assertThatThrownBy(() -> new CallAttempt.BlockedByTokenBucket(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
