package com.hyuk.flow_control.domain.expiry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestTtlTest {
    private static final Instant CREATED = Instant.parse("2026-10-14T00:00:00Z");
    private static final RequestTtl TTL = new RequestTtl(Duration.ofMinutes(10));

    @Test
    @DisplayName("생성 시각에 유효기간을 더한 시각을 넘으면 만료다")
    void expiresAfterTtl() {
        assertThat(TTL.isExpiredAt(CREATED, CREATED.plus(Duration.ofMinutes(10)))).isFalse();
        assertThat(TTL.isExpiredAt(CREATED, CREATED.plus(Duration.ofMinutes(10)).plusMillis(1))).isTrue();
    }

    @Test
    @DisplayName("요청 나이는 생성 시각부터 잰 시간이고, 생성 시각이 미래면 0 이다")
    void age() {
        assertThat(RequestTtl.ageAt(CREATED, CREATED.plusSeconds(30))).isEqualTo(Duration.ofSeconds(30));
        assertThat(RequestTtl.ageAt(CREATED, CREATED.minusSeconds(5))).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("유효기간은 0보다 커야 한다")
    void rejectsInvalidTtl() {
        assertThatThrownBy(() -> new RequestTtl(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestTtl(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
