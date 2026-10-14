package com.hyuk.flow_control.domain.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthStatusTest {
    private static final int REQUIRED_GOOD_COUNT = 3;

    @Test
    @DisplayName("시작 상태는 정상이고 연속 횟수는 0 이다")
    void initial() {
        assertThat(HealthStatus.initial()).isEqualTo(new HealthStatus(HealthState.HEALTHY, 0));
    }

    @ParameterizedTest(name = "{0}({1}) + {2} -> {3}({4})")
    @CsvSource({
            "HEALTHY,    0, GOOD,     HEALTHY,    0",
            "HEALTHY,    0, HOLD,     HEALTHY,    0",
            "HEALTHY,    0, DEGRADED, DEGRADED,   0",
            "HEALTHY,    0, CRITICAL, CRITICAL,   0",
            "DEGRADED,   0, GOOD,     RECOVERING, 1",
            "DEGRADED,   0, HOLD,     DEGRADED,   0",
            "DEGRADED,   0, DEGRADED, DEGRADED,   0",
            "DEGRADED,   0, CRITICAL, CRITICAL,   0",
            "CRITICAL,   0, GOOD,     RECOVERING, 1",
            "CRITICAL,   0, HOLD,     DEGRADED,   0",
            "CRITICAL,   0, DEGRADED, DEGRADED,   0",
            "CRITICAL,   0, CRITICAL, CRITICAL,   0",
            "RECOVERING, 1, GOOD,     RECOVERING, 2",
            "RECOVERING, 2, HOLD,     RECOVERING, 0",
            "RECOVERING, 1, DEGRADED, DEGRADED,   0",
            "RECOVERING, 1, CRITICAL, CRITICAL,   0",
            "RECOVERING, 2, GOOD,     HEALTHY,    0"
    })
    @DisplayName("직전 상태와 등급으로 다음 상태가 정해진다")
    void transitions(HealthState from, int fromCount, HealthGrade grade, HealthState to, int toCount) {
        HealthStatus next = new HealthStatus(from, fromCount).next(grade, REQUIRED_GOOD_COUNT);

        assertThat(next).isEqualTo(new HealthStatus(to, toCount));
    }

    @Test
    @DisplayName("위험에서 좋음이 30회 연속되면 30번째에 정상이 된다")
    void recoversAfterThirtyGoods() {
        HealthStatus status = new HealthStatus(HealthState.CRITICAL, 0);

        for (int i = 1; i <= 29; i++) {
            status = status.next(HealthGrade.GOOD, 30);
            assertThat(status).isEqualTo(new HealthStatus(HealthState.RECOVERING, i));
        }

        status = status.next(HealthGrade.GOOD, 30);
        assertThat(status).isEqualTo(new HealthStatus(HealthState.HEALTHY, 0));
    }

    @Test
    @DisplayName("회복 중에 악화가 나오면 악화로 가고, 다시 좋음이 나오면 연속 횟수는 처음부터 센다")
    void restartsCountAfterRelapse() {
        HealthStatus status = new HealthStatus(HealthState.RECOVERING, 2)
                .next(HealthGrade.DEGRADED, REQUIRED_GOOD_COUNT)
                .next(HealthGrade.GOOD, REQUIRED_GOOD_COUNT);

        assertThat(status).isEqualTo(new HealthStatus(HealthState.RECOVERING, 1));
    }

    @Test
    @DisplayName("악화 등급이 정한 횟수만큼 연속이어야 정상에서 악화로 바뀐다")
    void degradedNeedsStreakFromHealthy() {
        HealthStatus first = HealthStatus.initial().next(HealthGrade.DEGRADED, REQUIRED_GOOD_COUNT, 3);
        HealthStatus second = first.next(HealthGrade.DEGRADED, REQUIRED_GOOD_COUNT, 3);
        HealthStatus third = second.next(HealthGrade.DEGRADED, REQUIRED_GOOD_COUNT, 3);

        assertThat(first).isEqualTo(new HealthStatus(HealthState.HEALTHY, 0, 1));
        assertThat(second).isEqualTo(new HealthStatus(HealthState.HEALTHY, 0, 2));
        assertThat(third).isEqualTo(HealthStatus.of(HealthState.DEGRADED));
    }

    @Test
    @DisplayName("악화 연속 중에 좋음이나 사이값이 나오면 연속 횟수를 처음부터 센다")
    void goodOrHoldResetsStreak() {
        HealthStatus streak = HealthStatus.initial().next(HealthGrade.DEGRADED, REQUIRED_GOOD_COUNT, 3);

        assertThat(streak.next(HealthGrade.GOOD, REQUIRED_GOOD_COUNT, 3)).isEqualTo(HealthStatus.initial());
        assertThat(streak.next(HealthGrade.HOLD, REQUIRED_GOOD_COUNT, 3)).isEqualTo(HealthStatus.initial());
    }

    @Test
    @DisplayName("악화 연속 중이어도 위험은 바로 바뀐다")
    void criticalIgnoresStreak() {
        HealthStatus streak = HealthStatus.initial().next(HealthGrade.DEGRADED, REQUIRED_GOOD_COUNT, 3);

        assertThat(streak.next(HealthGrade.CRITICAL, REQUIRED_GOOD_COUNT, 3))
                .isEqualTo(HealthStatus.of(HealthState.CRITICAL));
    }

    @Test
    @DisplayName("회복 중에도 악화 등급이 정한 횟수만큼 연속이어야 악화로 바뀌고, 그동안 연속 좋음 횟수는 0 이다")
    void degradedNeedsStreakWhileRecovering() {
        HealthStatus recovering = new HealthStatus(HealthState.RECOVERING, 2);

        HealthStatus first = recovering.next(HealthGrade.DEGRADED, REQUIRED_GOOD_COUNT, 3);
        HealthStatus third = first.next(HealthGrade.DEGRADED, REQUIRED_GOOD_COUNT, 3)
                .next(HealthGrade.DEGRADED, REQUIRED_GOOD_COUNT, 3);

        assertThat(first).isEqualTo(new HealthStatus(HealthState.RECOVERING, 0, 1));
        assertThat(third).isEqualTo(HealthStatus.of(HealthState.DEGRADED));
    }

    @Test
    @DisplayName("상태가 없으면 만들 수 없다")
    void rejectsNullState() {
        assertThatThrownBy(() -> new HealthStatus(null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("연속 횟수가 음수면 만들 수 없다")
    void rejectsNegativeCount() {
        assertThatThrownBy(() -> new HealthStatus(HealthState.RECOVERING, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthStatus(HealthState.HEALTHY, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
