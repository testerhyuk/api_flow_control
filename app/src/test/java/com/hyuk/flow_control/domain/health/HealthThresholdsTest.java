package com.hyuk.flow_control.domain.health;

import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HealthThresholdsTest {
    private static final HealthThresholds THRESHOLDS = new HealthThresholds(10, 0.1, 0.5, 2.0);
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
