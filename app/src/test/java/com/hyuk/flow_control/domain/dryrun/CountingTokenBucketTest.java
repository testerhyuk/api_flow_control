package com.hyuk.flow_control.domain.dryrun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CountingTokenBucketTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    @DisplayName("처음에는 버킷 크기만큼 꺼낼 수 있다")
    void startsFull() {
        CountingTokenBucket bucket = new CountingTokenBucket(10, 3, 0);

        assertThat(bucket.tryConsume(0)).isTrue();
        assertThat(bucket.tryConsume(0)).isTrue();
        assertThat(bucket.tryConsume(0)).isTrue();
        assertThat(bucket.tryConsume(0)).isFalse();
    }

    @Test
    @DisplayName("rate 만큼 시간이 지나면 다시 채워진다")
    void refillsByRate() {
        CountingTokenBucket bucket = new CountingTokenBucket(10, 1, 0);
        bucket.tryConsume(0);

        assertThat(bucket.tryConsume(SECOND / 20)).isFalse();
        assertThat(bucket.tryConsume(SECOND / 10)).isTrue();
    }

    @Test
    @DisplayName("버킷 크기보다 많이 채우지 않는다")
    void capsAtCapacity() {
        CountingTokenBucket bucket = new CountingTokenBucket(10, 2, 0);

        assertThat(bucket.tryConsume(10 * SECOND)).isTrue();
        assertThat(bucket.tryConsume(10 * SECOND)).isTrue();
        assertThat(bucket.tryConsume(10 * SECOND)).isFalse();
    }

    @Test
    @DisplayName("rate 와 크기를 바꾸면 남은 토큰은 새 크기를 넘지 않는다")
    void changesRateAndCapacity() {
        CountingTokenBucket bucket = new CountingTokenBucket(10, 5, 0);

        bucket.change(1, 1, 0);

        assertThat(bucket.tryConsume(0)).isTrue();
        assertThat(bucket.tryConsume(0)).isFalse();
        assertThat(bucket.tryConsume(SECOND / 2)).isFalse();
        assertThat(bucket.tryConsume(SECOND)).isTrue();
    }

    @Test
    @DisplayName("rate 나 크기가 0 이하면 만들 수 없다")
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> new CountingTokenBucket(0, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CountingTokenBucket(1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
