package com.hyuk.flow_control.domain.limit;

import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RateAdjustmentRuleTest {
    private static final RateAdjustmentRule RULE = new RateAdjustmentRule(0.5, 0.05, 50, 5);
    private static final TargetSystemPolicy POLICY =
            new TargetSystemPolicy(100, Duration.ofMillis(150), 0.95, Duration.ofMillis(10));

    @Test
    @DisplayName("CRITICAL 이면 최소치로 내린다")
    void criticalGoesToFloor() {
        assertThat(RULE.next(95, HealthState.CRITICAL, 100, POLICY)).isEqualTo(5);
    }

    @Test
    @DisplayName("DEGRADED 면 최대치의 절반으로 내린다")
    void degradedHalves() {
        assertThat(RULE.next(95, HealthState.DEGRADED, 100, POLICY)).isCloseTo(47.5, within(1e-9));
    }

    @Test
    @DisplayName("DEGRADED 인데 이미 더 낮으면 그대로 둔다")
    void degradedKeepsLower() {
        assertThat(RULE.next(20, HealthState.DEGRADED, 100, POLICY)).isEqualTo(20);
    }

    @Test
    @DisplayName("회복 중이고 판정할 만큼 호출이 있으면 최대치의 5% 씩 올린다")
    void recoversStepByStep() {
        assertThat(RULE.next(47.5, HealthState.RECOVERING, 50, POLICY)).isCloseTo(52.25, within(1e-9));
    }

    @Test
    @DisplayName("호출이 적어 판정하지 못했으면 올리지 않는다")
    void keepsWhenNotGraded() {
        assertThat(RULE.next(47.5, HealthState.HEALTHY, 49, POLICY)).isEqualTo(47.5);
    }

    @Test
    @DisplayName("최대치를 넘지 않고, 최대치가 낮아지면 바로 내린다")
    void capsAtEffectiveRate() {
        assertThat(RULE.next(94, HealthState.HEALTHY, 100, POLICY)).isCloseTo(95, within(1e-9));

        TargetSystemPolicy lowered = new TargetSystemPolicy(50, Duration.ofMillis(150), 0.95, Duration.ofMillis(10));
        assertThat(RULE.next(95, HealthState.HEALTHY, 100, lowered)).isCloseTo(47.5, within(1e-9));
    }
}
