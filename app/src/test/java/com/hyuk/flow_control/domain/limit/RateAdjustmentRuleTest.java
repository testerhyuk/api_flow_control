package com.hyuk.flow_control.domain.limit;

import com.hyuk.flow_control.domain.health.HealthGrade;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RateAdjustmentRuleTest {
    private static final RateAdjustmentRule RULE = new RateAdjustmentRule(0.5, 50, 5);
    private static final TargetSystemPolicy POLICY =
            new TargetSystemPolicy(100, Duration.ofMillis(150), 0.95, Duration.ofMillis(10));

    @Test
    @DisplayName("CRITICAL 이면 최소치로 내린다")
    void criticalGoesToFloor() {
        assertThat(RULE.next(95, HealthState.CRITICAL, grade(HealthGrade.CRITICAL), 100, POLICY)).isEqualTo(5);
    }

    @Test
    @DisplayName("DEGRADED 면 새 호출 결과가 없어도 최대치의 절반으로 바로 내린다")
    void degradedCutsToHalf() {
        assertThat(RULE.next(95, HealthState.DEGRADED, grade(HealthGrade.DEGRADED), 0, POLICY))
                .isCloseTo(47.5, within(1e-9));
    }

    @Test
    @DisplayName("올리는 폭은 지금 한도 × (1 - 안전 비율) / 안전 비율 이다")
    void raisesBySafetyMarginOfCurrentRate() {
        assertThat(RULE.next(38, HealthState.RECOVERING, grade(HealthGrade.GOOD), 50, POLICY))
                .isCloseTo(40, within(1e-9));

        TargetSystemPolicy margin08 = new TargetSystemPolicy(100, Duration.ofMillis(150), 0.8, Duration.ofMillis(10));
        assertThat(RULE.next(40, HealthState.RECOVERING, grade(HealthGrade.GOOD), 50, margin08))
                .isCloseTo(50, within(1e-9));
    }

    @Test
    @DisplayName("바뀐 뒤 새 호출 결과가 판정 개수만큼 쌓이지 않았으면 올리지 않는다")
    void keepsUntilEnoughSamplesSinceChange() {
        assertThat(RULE.next(38, HealthState.RECOVERING, grade(HealthGrade.GOOD), 49, POLICY)).isEqualTo(38);
        assertThat(RULE.next(38, HealthState.HEALTHY, grade(HealthGrade.GOOD), 49, POLICY)).isEqualTo(38);
        assertThat(RULE.next(19, HealthState.DEGRADED, grade(HealthGrade.HOLD), 49, POLICY)).isEqualTo(19);
    }

    @Test
    @DisplayName("회복 중에는 GOOD 일 때만 올리고 HOLD 면 그대로 둔다")
    void recoveringRaisesOnlyOnGood() {
        assertThat(RULE.next(38, HealthState.RECOVERING, grade(HealthGrade.HOLD), 50, POLICY)).isEqualTo(38);
    }

    @Test
    @DisplayName("정상이면 GOOD 이든 HOLD 든 올린다")
    void healthyRaisesOnGoodOrHold() {
        assertThat(RULE.next(38, HealthState.HEALTHY, grade(HealthGrade.GOOD), 50, POLICY))
                .isCloseTo(40, within(1e-9));
        assertThat(RULE.next(38, HealthState.HEALTHY, grade(HealthGrade.HOLD), 50, POLICY))
                .isCloseTo(40, within(1e-9));
        assertThat(RULE.next(38, HealthState.HEALTHY, grade(HealthGrade.DEGRADED), 50, POLICY)).isEqualTo(38);
    }

    @Test
    @DisplayName("DEGRADED 인데 더 낮으면 등급과 상관없이 최대치의 절반까지 올린다")
    void degradedRaisesUpToHalf() {
        assertThat(RULE.next(19, HealthState.DEGRADED, grade(HealthGrade.DEGRADED), 50, POLICY))
                .isCloseTo(20, within(1e-9));
        assertThat(RULE.next(19, HealthState.DEGRADED, grade(HealthGrade.HOLD), 50, POLICY))
                .isCloseTo(20, within(1e-9));
        assertThat(RULE.next(47, HealthState.DEGRADED, grade(HealthGrade.HOLD), 50, POLICY))
                .isCloseTo(47.5, within(1e-9));
    }

    @Test
    @DisplayName("등급이 없는 판정에서는 올리지 않는다")
    void keepsWithoutGrade() {
        assertThat(RULE.next(38, HealthState.HEALTHY, Optional.empty(), 50, POLICY)).isEqualTo(38);
        assertThat(RULE.next(19, HealthState.DEGRADED, Optional.empty(), 50, POLICY)).isEqualTo(19);
    }

    @Test
    @DisplayName("최대치를 넘지 않고, 최대치가 낮아지면 바로 내린다")
    void capsAtEffectiveRate() {
        assertThat(RULE.next(94, HealthState.HEALTHY, grade(HealthGrade.GOOD), 50, POLICY))
                .isCloseTo(95, within(1e-9));

        TargetSystemPolicy lowered = new TargetSystemPolicy(50, Duration.ofMillis(150), 0.95, Duration.ofMillis(10));
        assertThat(RULE.next(95, HealthState.HEALTHY, Optional.empty(), 0, lowered)).isCloseTo(47.5, within(1e-9));
    }

    private static Optional<HealthGrade> grade(HealthGrade grade) {
        return Optional.of(grade);
    }
}
