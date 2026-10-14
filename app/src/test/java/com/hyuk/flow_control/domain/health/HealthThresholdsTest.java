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

class HealthThresholdsTest {
    private static final HealthThresholds THRESHOLDS = new HealthThresholds(10, 0.1, 0.5, 2.0);
    private static final HealthThresholds HYSTERESIS = new HealthThresholds(20, 0.1, 0.5, 2.0, 0.05, 0.25, 1.5);
    private static final Duration BASELINE = Duration.ofMillis(100);

    @Test
    @DisplayName("호출이 최소 개수보다 적으면 판정하지 않는다")
    void noGradeWithFewCalls() {
        assertThat(THRESHOLDS.gradeOf(statistics(9, 0, 50), BASELINE)).isEmpty();
    }

    @Test
    @DisplayName("실패율이 낮고 p95 가 기준의 두 배보다 작으면 GOOD 이다")
    void good() {
        assertThat(THRESHOLDS.gradeOf(statistics(10, 0, 199), BASELINE)).contains(HealthGrade.GOOD);
    }

    @Test
    @DisplayName("p95 가 기준의 두 배 이상이면 DEGRADED 다")
    void degradedBySlowness() {
        assertThat(THRESHOLDS.gradeOf(statistics(10, 0, 200), BASELINE)).contains(HealthGrade.DEGRADED);
    }

    @Test
    @DisplayName("실패율이 degraded 기준 이상이면 DEGRADED 다")
    void degradedByFailures() {
        assertThat(THRESHOLDS.gradeOf(statistics(9, 1, 50), BASELINE)).contains(HealthGrade.DEGRADED);
    }

    @Test
    @DisplayName("실패율이 critical 기준 이상이면 CRITICAL 이다")
    void critical() {
        assertThat(THRESHOLDS.gradeOf(statistics(5, 5, 50), BASELINE)).contains(HealthGrade.CRITICAL);
    }

    @Test
    @DisplayName("같은 호출이라도 기준 latency 가 낮으면 DEGRADED 가 된다")
    void dependsOnBaseline() {
        OutcomeStatistics statistics = statistics(10, 0, 150);

        assertThat(THRESHOLDS.gradeOf(statistics, BASELINE)).contains(HealthGrade.GOOD);
        assertThat(THRESHOLDS.gradeOf(statistics, Duration.ofMillis(75))).contains(HealthGrade.DEGRADED);
    }

    @Test
    @DisplayName("복귀 기준을 따로 두지 않으면 사이값이 나오지 않는다")
    void noHoldWithoutHysteresis() {
        assertThat(THRESHOLDS.gradeOf(statistics(91, 9, 150), BASELINE)).contains(HealthGrade.GOOD);
    }

    @Test
    @DisplayName("실패율이 복귀 기준 이상 진입 기준 미만이면 사이값이다")
    void holdBetweenFailureThresholds() {
        assertThat(HYSTERESIS.gradeOf(statistics(19, 1, 50), BASELINE)).contains(HealthGrade.HOLD);
        assertThat(HYSTERESIS.gradeOf(statistics(39, 1, 50), BASELINE)).contains(HealthGrade.GOOD);
    }

    @Test
    @DisplayName("p95 가 복귀 기준선 이상 진입 기준선 미만이면 사이값이다")
    void holdBetweenLatencyThresholds() {
        assertThat(HYSTERESIS.gradeOf(statistics(20, 0, 150), BASELINE)).contains(HealthGrade.HOLD);
        assertThat(HYSTERESIS.gradeOf(statistics(20, 0, 149), BASELINE)).contains(HealthGrade.GOOD);
        assertThat(HYSTERESIS.gradeOf(statistics(20, 0, 200), BASELINE)).contains(HealthGrade.DEGRADED);
    }

    @Test
    @DisplayName("위험 상태에서는 위험 복귀 기준 아래로 내려가야 위험 등급에서 벗어난다")
    void criticalUsesRecoveryLineWhileCritical() {
        OutcomeStatistics thirtyPercent = statistics(14, 6, 50);

        assertThat(HYSTERESIS.gradeOf(thirtyPercent, BASELINE, HealthState.HEALTHY)).contains(HealthGrade.DEGRADED);
        assertThat(HYSTERESIS.gradeOf(thirtyPercent, BASELINE, HealthState.CRITICAL)).contains(HealthGrade.CRITICAL);
    }

    @Test
    @DisplayName("복귀 기준선과 진입 기준선은 baseline 에 배수를 곱한 값이다")
    void latencyLines() {
        assertThat(HYSTERESIS.latencyThreshold(BASELINE)).isEqualTo(Duration.ofMillis(200));
        assertThat(HYSTERESIS.recoveryLatencyThreshold(BASELINE)).isEqualTo(Duration.ofMillis(150));
        assertThat(HYSTERESIS.withoutHysteresis().recoveryLatencyThreshold(BASELINE)).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("복귀 기준이 진입 기준보다 바깥이면 만들 수 없다")
    void rejectsRecoveryOutsideEntry() {
        assertThatThrownBy(() -> new HealthThresholds(20, 0.1, 0.5, 2.0, 0.2, 0.25, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthThresholds(20, 0.1, 0.5, 2.0, 0.05, 0.6, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthThresholds(20, 0.1, 0.5, 2.0, 0.05, 0.25, 2.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OutcomeStatistics statistics(int successes, int failures, long latencyMillis) {
        List<RecordedOutcome> outcomes = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < successes; i++) {
            outcomes.add(new RecordedOutcome(OutcomeKind.SUCCESS, Duration.ofMillis(latencyMillis), now));
        }
        for (int i = 0; i < failures; i++) {
            outcomes.add(new RecordedOutcome(OutcomeKind.SERVER_ERROR, Duration.ofMillis(latencyMillis), now));
        }
        return OutcomeStatistics.from(outcomes);
    }
}
