package com.hyuk.flow_control.domain.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class TargetSystemPolicyTest {
    private static final TargetSystemPolicy TARGET_A = new TargetSystemPolicy(
            100, Duration.ofMillis(100), 0.95, Duration.ofMillis(10));

    @Test
    @DisplayName("실제 Rate 는 max-tps 에 안전 여유를 곱한 값이다")
    void effectiveRate() {
        assertThat(TARGET_A.effectiveRate()).isCloseTo(95.0, within(1e-9));
    }

    @Test
    @DisplayName("허가 개수는 실제 Rate 에 기준 RTT 를 곱해서 올림한 값이다")
    void concurrencyLimitRoundsUp() {
        assertThat(TARGET_A.concurrencyLimit()).isEqualTo(10);
    }

    @Test
    @DisplayName("곱한 값이 정수로 떨어지면 허가 개수는 그 값 그대로다")
    void concurrencyLimitKeepsExactInteger() {
        TargetSystemPolicy targetC = new TargetSystemPolicy(
                20, Duration.ofSeconds(1), 0.95, Duration.ofMillis(10));

        assertThat(targetC.concurrencyLimit()).isEqualTo(19);
    }

    @Test
    @DisplayName("버스트 용량은 실제 Rate 에 burst-window 를 곱한 값이다")
    void burstCapacity() {
        TargetSystemPolicy policy = new TargetSystemPolicy(
                1000, Duration.ofMillis(100), 1.0, Duration.ofMillis(10));

        assertThat(policy.burstCapacity()).isEqualTo(10);
    }

    @Test
    @DisplayName("충전 간격은 1초를 실제 Rate 로 나눈 값이다")
    void refillInterval() {
        assertThat(TARGET_A.refillInterval()).isEqualTo(Duration.ofNanos(10_526_316));
    }

    @Test
    @DisplayName("계산 결과가 1보다 작아도 허가 개수와 버스트 용량은 최소 1이다")
    void atLeastOne() {
        TargetSystemPolicy policy = new TargetSystemPolicy(
                1, Duration.ofMillis(100), 0.95, Duration.ofMillis(10));

        assertThat(policy.concurrencyLimit()).isEqualTo(1);
        assertThat(policy.burstCapacity()).isEqualTo(1);
    }

    @Test
    @DisplayName("max-tps 가 0 이하면 만들 수 없다")
    void rejectsNonPositiveMaxTps() {
        assertThatThrownBy(() -> new TargetSystemPolicy(
                0, Duration.ofMillis(100), 0.95, Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("baseline-rtt 가 0 이면 만들 수 없다")
    void rejectsZeroBaselineRtt() {
        assertThatThrownBy(() -> new TargetSystemPolicy(
                100, Duration.ZERO, 0.95, Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("safety-margin 이 0 이하이거나 1 을 넘으면 만들 수 없다")
    void rejectsInvalidSafetyMargin() {
        assertThatThrownBy(() -> new TargetSystemPolicy(
                100, Duration.ofMillis(100), 0.0, Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new TargetSystemPolicy(
                100, Duration.ofMillis(100), 1.1, Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("burst-window 가 0 이면 만들 수 없다")
    void rejectsZeroBurstWindow() {
        assertThatThrownBy(() -> new TargetSystemPolicy(
                100, Duration.ofMillis(100), 0.95, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("baseline-rtt 가 없으면 만들 수 없다")
    void rejectsNullBaselineRtt() {
        assertThatThrownBy(() -> new TargetSystemPolicy(
                100, null, 0.95, Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("burst-window 가 없으면 만들 수 없다")
    void rejectsNullBurstWindow() {
        assertThatThrownBy(() -> new TargetSystemPolicy(
                100, Duration.ofMillis(100), 0.95, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}