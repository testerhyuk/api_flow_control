package com.hyuk.flow_control.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntakePropertiesTest {
    private static final Duration LAG_INTERVAL = Duration.ofSeconds(5);

    @Test
    @DisplayName("request-ttl 이 재시도 대기 합계(371초)보다 길지 않으면 만들 수 없다")
    void rejectsTtlNotLongerThanRetryDelays() {
        assertThatThrownBy(() -> new IntakeProperties(Duration.ofMinutes(5), LAG_INTERVAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntakeProperties(Duration.ofSeconds(371), LAG_INTERVAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("request-ttl 이 재시도 대기 합계보다 길면 만들 수 있다")
    void acceptsTtlLongerThanRetryDelays() {
        assertThat(new IntakeProperties(Duration.ofSeconds(372), LAG_INTERVAL).requestTtl())
                .isEqualTo(Duration.ofSeconds(372));
        assertThat(new IntakeProperties(Duration.ofMinutes(10), LAG_INTERVAL).requestTtl())
                .isEqualTo(Duration.ofMinutes(10));
    }
}
