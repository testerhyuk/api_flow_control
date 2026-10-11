package com.hyuk.flow_control.domain.health;

import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordedOutcomeTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("kind, latency, 기록 시점을 그대로 담는다")
    void holdsValues() {
        RecordedOutcome outcome = new RecordedOutcome(OutcomeKind.SUCCESS, Duration.ofMillis(120), NOW);

        assertThat(outcome.kind()).isEqualTo(OutcomeKind.SUCCESS);
        assertThat(outcome.latency()).isEqualTo(Duration.ofMillis(120));
        assertThat(outcome.recordedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("latency 가 0 이면 만들 수 있다")
    void allowsZeroLatency() {
        RecordedOutcome outcome = new RecordedOutcome(OutcomeKind.SUCCESS, Duration.ZERO, NOW);

        assertThat(outcome.latency()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("kind 가 없으면 만들 수 없다")
    void rejectsNullKind() {
        assertThatThrownBy(() -> new RecordedOutcome(null, Duration.ofMillis(120), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("latency 가 없거나 음수면 만들 수 없다")
    void rejectsInvalidLatency() {
        assertThatThrownBy(() -> new RecordedOutcome(OutcomeKind.SUCCESS, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecordedOutcome(OutcomeKind.SUCCESS, Duration.ofMillis(-1), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기록 시점이 없으면 만들 수 없다")
    void rejectsNullRecordedAt() {
        assertThatThrownBy(() -> new RecordedOutcome(OutcomeKind.SUCCESS, Duration.ofMillis(120), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
