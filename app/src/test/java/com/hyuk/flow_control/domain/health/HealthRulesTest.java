package com.hyuk.flow_control.domain.health;

import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthRulesTest {
    private static final HealthThresholds THRESHOLDS = new HealthThresholds(20, 0.1, 0.5, 2.0, 0.05, 0.25, 1.5);
    private static final Duration BASELINE = Duration.ofMillis(100);
    private static final HealthRules ON = new HealthRules(THRESHOLDS, 3, 3, true);
    private static final HealthRules OFF = new HealthRules(THRESHOLDS, 3, 3, false);

    @Test
    @DisplayName("건수가 모자라면 상태를 그대로 두고 등급도 없다")
    void keepsStatusWithoutEnoughSamples() {
        HealthRules.Judgment judgment = ON.judge(HealthStatus.of(HealthState.DEGRADED), statistics(5, 5), BASELINE);

        assertThat(judgment.next()).isEqualTo(HealthStatus.of(HealthState.DEGRADED));
        assertThat(judgment.grade()).isEmpty();
        assertThat(judgment.suppressedBy()).isEmpty();
    }

    @Test
    @DisplayName("진동 방지가 켜져 있으면 사이값에서 악화를 유지하고, 히스테리시스가 막았다고 알려준다")
    void hysteresisSuppresses() {
        HealthRules.Judgment judgment = ON.judge(HealthStatus.of(HealthState.DEGRADED), statistics(93, 7), BASELINE);

        assertThat(judgment.next().state()).isEqualTo(HealthState.DEGRADED);
        assertThat(judgment.grade()).contains(HealthGrade.HOLD);
        assertThat(judgment.suppressedBy()).contains(SuppressedBy.HYSTERESIS);
    }

    @Test
    @DisplayName("진동 방지가 켜져 있으면 악화 등급 한 번으로는 바뀌지 않고, 연속 관측이 막았다고 알려준다")
    void streakSuppresses() {
        HealthRules.Judgment judgment = ON.judge(HealthStatus.initial(), statistics(80, 20), BASELINE);

        assertThat(judgment.next()).isEqualTo(new HealthStatus(HealthState.HEALTHY, 0, 1));
        assertThat(judgment.suppressedBy()).contains(SuppressedBy.CONSECUTIVE);
    }

    @Test
    @DisplayName("진동 방지를 끄면 복귀 기준과 진입 기준이 같고 악화 등급 한 번에 바뀐다")
    void offUsesPlainRules() {
        HealthRules.Judgment degraded = OFF.judge(HealthStatus.initial(), statistics(80, 20), BASELINE);
        HealthRules.Judgment recovering = OFF.judge(HealthStatus.of(HealthState.DEGRADED), statistics(93, 7), BASELINE);

        assertThat(degraded.next().state()).isEqualTo(HealthState.DEGRADED);
        assertThat(recovering.next().state()).isEqualTo(HealthState.RECOVERING);
        assertThat(degraded.suppressedBy()).isEmpty();
        assertThat(recovering.suppressedBy()).isEmpty();
        assertThat(OFF.activeThresholds()).isEqualTo(THRESHOLDS.withoutHysteresis());
    }

    @Test
    @DisplayName("진동 방지를 꺼도 회복은 정한 횟수만큼 연속 좋음이 있어야 한다")
    void offKeepsRecoveryCount() {
        HealthStatus status = HealthStatus.of(HealthState.CRITICAL);
        for (int i = 0; i < 3; i++) {
            status = OFF.judge(status, statistics(100, 0), BASELINE).next();
        }

        assertThat(status.state()).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    @DisplayName("결과가 같으면 막은 것이 없다")
    void noSuppressionWhenSame() {
        HealthRules.Judgment judgment = ON.judge(HealthStatus.initial(), statistics(50, 50), BASELINE);

        assertThat(judgment.next().state()).isEqualTo(HealthState.CRITICAL);
        assertThat(judgment.suppressedBy()).isEmpty();
    }

    @Test
    @DisplayName("연속 횟수가 1보다 작으면 만들 수 없다")
    void rejectsInvalidCounts() {
        assertThatThrownBy(() -> new HealthRules(THRESHOLDS, 0, 3, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthRules(THRESHOLDS, 3, 0, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthRules(null, 3, 3, true)).isInstanceOf(IllegalArgumentException.class);
    }

    private static OutcomeStatistics statistics(int successes, int failures) {
        List<RecordedOutcome> outcomes = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < successes; i++) {
            outcomes.add(new RecordedOutcome(OutcomeKind.SUCCESS, Duration.ofMillis(50), now));
        }
        for (int i = 0; i < failures; i++) {
            outcomes.add(new RecordedOutcome(OutcomeKind.SERVER_ERROR, Duration.ofMillis(50), now));
        }
        return OutcomeStatistics.from(outcomes);
    }
}
