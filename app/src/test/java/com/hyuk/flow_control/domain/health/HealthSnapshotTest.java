package com.hyuk.flow_control.domain.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthSnapshotTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("시작 스냅샷은 정상이고 판정 근거는 비어 있다")
    void initial() {
        assertThat(HealthSnapshot.initial())
                .isEqualTo(new HealthSnapshot(HealthState.HEALTHY, 0, 0.0, null, null));
    }

    @Test
    @DisplayName("상태와 판정 근거를 그대로 담는다")
    void holdsValues() {
        HealthSnapshot snapshot = new HealthSnapshot(HealthState.DEGRADED, 120, 0.25, Duration.ofMillis(210), NOW);

        assertThat(snapshot.state()).isEqualTo(HealthState.DEGRADED);
        assertThat(snapshot.sampleCount()).isEqualTo(120);
        assertThat(snapshot.failureRate()).isEqualTo(0.25);
        assertThat(snapshot.p95()).isEqualTo(Duration.ofMillis(210));
        assertThat(snapshot.evaluatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("나쁘다 비율이 0 이나 1 이면 만들 수 있다")
    void allowsFailureRateBoundaries() {
        assertThat(new HealthSnapshot(HealthState.HEALTHY, 10, 0.0, null, NOW).failureRate()).isZero();
        assertThat(new HealthSnapshot(HealthState.CRITICAL, 10, 1.0, null, NOW).failureRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("상태가 없으면 만들 수 없다")
    void rejectsNullState() {
        assertThatThrownBy(() -> new HealthSnapshot(null, 0, 0.0, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("건수가 음수면 만들 수 없다")
    void rejectsNegativeSampleCount() {
        assertThatThrownBy(() -> new HealthSnapshot(HealthState.HEALTHY, -1, 0.0, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("나쁘다 비율이 0 보다 작거나 1 보다 크면 만들 수 없다")
    void rejectsFailureRateOutOfRange() {
        assertThatThrownBy(() -> new HealthSnapshot(HealthState.HEALTHY, 10, -0.01, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthSnapshot(HealthState.HEALTHY, 10, 1.01, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
