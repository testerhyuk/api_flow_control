package com.hyuk.flow_control.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthPropertiesTest {
    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final Duration RECOVERY = Duration.ofSeconds(30);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("설정 파일의 값이 그대로 들어온다")
    void bindsFromProperties() {
        contextRunner
                .withPropertyValues(
                        "flow-control.health.window=10s",
                        "flow-control.health.evaluation-interval=1s",
                        "flow-control.health.minimum-calls=50",
                        "flow-control.health.degraded-failure-rate=0.10",
                        "flow-control.health.critical-failure-rate=0.50",
                        "flow-control.health.degraded-latency-multiplier=2.0",
                        "flow-control.health.recovery-duration=30s")
                .run(context -> assertThat(context.getBean(HealthProperties.class))
                        .isEqualTo(new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 2.0, RECOVERY)));
    }

    @Test
    @DisplayName("설정 값이 잘못되면 앱이 뜨지 않는다")
    void failsToStartWithInvalidValue() {
        contextRunner
                .withPropertyValues(
                        "flow-control.health.window=10s",
                        "flow-control.health.evaluation-interval=1s",
                        "flow-control.health.minimum-calls=0",
                        "flow-control.health.degraded-failure-rate=0.10",
                        "flow-control.health.critical-failure-rate=0.50",
                        "flow-control.health.degraded-latency-multiplier=2.0",
                        "flow-control.health.recovery-duration=30s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("설정이 하나도 없으면 앱이 뜨지 않는다")
    void failsToStartWithoutProperties() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("window 가 없거나 0 이하면 만들 수 없다")
    void rejectsInvalidWindow() {
        assertThatThrownBy(() -> new HealthProperties(null, INTERVAL, 50, 0.10, 0.50, 2.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(Duration.ZERO, INTERVAL, 50, 0.10, 0.50, 2.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(Duration.ofSeconds(-1), INTERVAL, 50, 0.10, 0.50, 2.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("evaluation-interval 이 없거나 0 이하면 만들 수 없다")
    void rejectsInvalidInterval() {
        assertThatThrownBy(() -> new HealthProperties(WINDOW, null, 50, 0.10, 0.50, 2.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(WINDOW, Duration.ZERO, 50, 0.10, 0.50, 2.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(WINDOW, Duration.ofSeconds(-1), 50, 0.10, 0.50, 2.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("minimum-calls 가 0 이하면 만들 수 없다")
    void rejectsInvalidMinimumCalls() {
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 0, 0.10, 0.50, 2.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, -1, 0.10, 0.50, 2.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("degraded-failure-rate 가 0 이하이거나 critical-failure-rate 이상이면 만들 수 없다")
    void rejectsInvalidDegradedFailureRate() {
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.0, 0.50, 2.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.50, 0.50, 2.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.60, 0.50, 2.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("critical-failure-rate 는 1 까지 되고 1 을 넘으면 만들 수 없다")
    void criticalFailureRateUpToOne() {
        assertThat(new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 1.0, 2.0, RECOVERY).criticalFailureRate())
                .isEqualTo(1.0);
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 1.01, 2.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("degraded-latency-multiplier 가 1 이하면 만들 수 없다")
    void rejectsInvalidMultiplier() {
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 1.0, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 0.5, RECOVERY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("recovery-duration 이 없거나 0 이하면 만들 수 없다")
    void rejectsInvalidRecoveryDuration() {
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 2.0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 2.0, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 2.0, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("recovery-duration 이 evaluation-interval 보다 길지 않으면 만들 수 없다")
    void rejectsRecoveryDurationNotLongerThanInterval() {
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 2.0, INTERVAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 2.0, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("recovery-duration 이 evaluation-interval 보다 조금이라도 길면 만들 수 있다")
    void allowsRecoveryDurationLongerThanInterval() {
        Duration recovery = Duration.ofMillis(1001);

        assertThat(new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 2.0, recovery).recoveryDuration())
                .isEqualTo(recovery);
    }

    @Test
    @DisplayName("진동 방지, 저트래픽, baseline 설정이 없으면 출발값을 쓴다")
    void usesStartingValuesWhenMissing() {
        HealthProperties properties = new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 2.0, RECOVERY);

        assertThat(properties.antiOscillation()).isTrue();
        assertThat(properties.degradedStreak()).isEqualTo(3);
        assertThat(properties.recoveryThreshold())
                .isEqualTo(new HealthProperties.RecoveryThreshold(0.05, 0.25, 1.5));
        assertThat(properties.lowTraffic()).isEqualTo(new HealthProperties.LowTraffic(3, 0.5, 3, 3));
        assertThat(properties.baselineAuto()).isTrue();
        assertThat(properties.baseline())
                .isEqualTo(new HealthProperties.Baseline(Duration.ofMinutes(10), 100, 0.5, 1.5));
        assertThat(properties.requiredGoodCount()).isEqualTo(30);
    }

    @Test
    @DisplayName("진동 방지, 저트래픽, baseline 설정을 설정 파일에서 읽는다")
    void bindsNewSettings() {
        contextRunner
                .withPropertyValues(
                        "flow-control.health.window=10s",
                        "flow-control.health.evaluation-interval=1s",
                        "flow-control.health.minimum-calls=50",
                        "flow-control.health.degraded-failure-rate=0.10",
                        "flow-control.health.critical-failure-rate=0.50",
                        "flow-control.health.degraded-latency-multiplier=2.0",
                        "flow-control.health.recovery-duration=30s",
                        "flow-control.health.anti-oscillation=false",
                        "flow-control.health.recovery-threshold.degraded-failure-rate=0.07",
                        "flow-control.health.recovery-threshold.critical-failure-rate=0.30",
                        "flow-control.health.recovery-threshold.latency-multiplier=1.8",
                        "flow-control.health.degraded-streak=5",
                        "flow-control.health.low-traffic.enter-after=4",
                        "flow-control.health.low-traffic.rate-ratio=0.4",
                        "flow-control.health.low-traffic.consecutive-failures=5",
                        "flow-control.health.low-traffic.consecutive-timeouts=6",
                        "flow-control.health.baseline-auto=false",
                        "flow-control.health.baseline.window=5m",
                        "flow-control.health.baseline.minimum-samples=200",
                        "flow-control.health.baseline.lower-ratio=0.6",
                        "flow-control.health.baseline.upper-ratio=1.4")
                .run(context -> {
                    HealthProperties properties = context.getBean(HealthProperties.class);
                    assertThat(properties.antiOscillation()).isFalse();
                    assertThat(properties.recoveryThreshold())
                            .isEqualTo(new HealthProperties.RecoveryThreshold(0.07, 0.30, 1.8));
                    assertThat(properties.degradedStreak()).isEqualTo(5);
                    assertThat(properties.lowTraffic()).isEqualTo(new HealthProperties.LowTraffic(4, 0.4, 5, 6));
                    assertThat(properties.baselineAuto()).isFalse();
                    assertThat(properties.baseline())
                            .isEqualTo(new HealthProperties.Baseline(Duration.ofMinutes(5), 200, 0.6, 1.4));
                });
    }

    @Test
    @DisplayName("복귀 기준이 진입 기준보다 바깥이면 만들 수 없다")
    void rejectsRecoveryOutsideEntry() {
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 2.0, RECOVERY,
                true, new HealthProperties.RecoveryThreshold(0.2, 0.25, 1.5), 3, null, true, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 2.0, RECOVERY,
                true, new HealthProperties.RecoveryThreshold(0.05, 0.25, 2.5), 3, null, true, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HealthProperties(WINDOW, INTERVAL, 50, 0.10, 0.50, 2.0, RECOVERY,
                true, null, 0, null, true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration
    @EnableConfigurationProperties(HealthProperties.class)
    static class TestConfig {
    }
}
