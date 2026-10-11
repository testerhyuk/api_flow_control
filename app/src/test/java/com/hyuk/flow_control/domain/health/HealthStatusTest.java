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
            "HEALTHY,    0, DEGRADED, DEGRADED,   0",
            "HEALTHY,    0, CRITICAL, CRITICAL,   0",
            "DEGRADED,   0, GOOD,     RECOVERING, 1",
            "DEGRADED,   0, DEGRADED, DEGRADED,   0",
            "DEGRADED,   0, CRITICAL, CRITICAL,   0",
            "CRITICAL,   0, GOOD,     RECOVERING, 1",
            "CRITICAL,   0, DEGRADED, DEGRADED,   0",
            "CRITICAL,   0, CRITICAL, CRITICAL,   0",
            "RECOVERING, 1, GOOD,     RECOVERING, 2",
            "RECOVERING, 1, DEGRADED, CRITICAL,   0",
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
    @DisplayName("회복 중에 한 번이라도 좋음이 아니면 위험으로 돌아가고 연속 횟수는 처음부터 다시 센다")
    void restartsCountAfterRelapse() {
        HealthStatus status = new HealthStatus(HealthState.RECOVERING, 2)
                .next(HealthGrade.DEGRADED, REQUIRED_GOOD_COUNT)
                .next(HealthGrade.GOOD, REQUIRED_GOOD_COUNT);

        assertThat(status).isEqualTo(new HealthStatus(HealthState.RECOVERING, 1));
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
    }
}
