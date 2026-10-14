package com.hyuk.mock_target.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockTargetPropertiesTest {
    private static final Duration RETENTION = Duration.ofMinutes(10);

    @Test
    @DisplayName("실행할 대상의 설정을 고른다")
    void selectsTarget() {
        MockTargetProperties properties = new MockTargetProperties("target-a", RETENTION, Map.of("target-a", system(9001, 9101)));

        assertThat(properties.selected().port()).isEqualTo(9001);
    }

    @Test
    @DisplayName("대상 이름이 없거나 설정에 없는 이름이면 만들 수 없다")
    void rejectsUnknownTarget() {
        Map<String, MockTargetProperties.TargetSystem> systems = Map.of("target-a", system(9001, 9101));

        assertThatThrownBy(() -> new MockTargetProperties(null, RETENTION, systems))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MockTargetProperties("target-z", RETENTION, systems))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("중복 기억 기간이 0 이하면 만들 수 없다")
    void rejectsNonPositiveRetention() {
        assertThatThrownBy(() -> new MockTargetProperties("target-a", Duration.ZERO, Map.of("target-a", system(9001, 9101))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("요청 포트와 관리용 포트가 같으면 만들 수 없다")
    void rejectsSamePorts() {
        assertThatThrownBy(() -> system(9001, 9001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("평소 실패 비율의 합이 1 을 넘으면 만들 수 없다")
    void rejectsRatesOverOne() {
        assertThatThrownBy(() -> new MockTargetProperties.TargetSystem(
                9001, 9101, 10, 0, 100, 1.5, 2.6, 10, Duration.ofSeconds(10), 0.6, 0.5, ResponseFormat.STATUS_CODE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("배수가 1 보다 작으면 만들 수 없다")
    void rejectsMultipliersBelowOne() {
        assertThatThrownBy(() -> new MockTargetProperties.TargetSystem(
                9001, 9101, 10, 0, 100, 0.9, 2.6, 10, Duration.ofSeconds(10), 0, 0, ResponseFormat.STATUS_CODE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MockTargetProperties.TargetSystem(
                9001, 9101, 10, 0, 100, 1.5, 0.9, 10, Duration.ofSeconds(10), 0, 0, ResponseFormat.STATUS_CODE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MockTargetProperties.TargetSystem system(int port, int adminPort) {
        return new MockTargetProperties.TargetSystem(
                port, adminPort, 10, 0, 100, 1.5, 2.6, 10, Duration.ofSeconds(10), 0.01, 0.02, ResponseFormat.STATUS_CODE);
    }
}
