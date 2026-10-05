package com.hyuk.flow_control.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetSystemPropertiesTest {
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final Duration BASELINE_RTT = Duration.ofMillis(100);
    private static final Duration BURST_WINDOW = Duration.ofMillis(10);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("설정 파일의 값이 그대로 들어온다")
    void bindsFromProperties() {
        contextRunner
                .withPropertyValues(
                        "flow-control.target-systems.target-a.url=http://localhost:9001",
                        "flow-control.target-systems.target-a.connect-timeout=1s",
                        "flow-control.target-systems.target-a.read-timeout=3s",
                        "flow-control.target-systems.target-a.max-tps=100",
                        "flow-control.target-systems.target-a.baseline-rtt=100ms",
                        "flow-control.target-systems.target-a.safety-margin=0.95",
                        "flow-control.target-systems.target-a.burst-window=10ms")
                .run(context -> {
                    TargetSystemProperties properties = context.getBean(TargetSystemProperties.class);

                    assertThat(properties.targetSystems()).isEqualTo(Map.of(
                            "target-a", new TargetSystemProperties.TargetSystem(
                                    "http://localhost:9001", Duration.ofSeconds(1), Duration.ofSeconds(3),
                                    100, Duration.ofMillis(100), 0.95, Duration.ofMillis(10))
                    ));
                });
    }

    @Test
    @DisplayName("설정 값이 잘못되면 앱이 뜨지 않는다")
    void failsToStartWithInvalidValue() {
        contextRunner
                .withPropertyValues(
                        "flow-control.target-systems.target-a.url=http://localhost:9001",
                        "flow-control.target-systems.target-a.connect-timeout=1s",
                        "flow-control.target-systems.target-a.read-timeout=3s",
                        "flow-control.target-systems.target-a.max-tps=0",
                        "flow-control.target-systems.target-a.baseline-rtt=100ms",
                        "flow-control.target-systems.target-a.safety-margin=0.95",
                        "flow-control.target-systems.target-a.burst-window=10ms")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("대상 시스템이 하나도 없으면 앱이 뜨지 않는다")
    void failsToStartWithoutTargetSystems() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("대상 시스템이 없거나 비어 있으면 만들 수 없다")
    void rejectsMissingTargetSystems() {
        assertThatThrownBy(() -> new TargetSystemProperties(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("url 이 없거나 비어 있으면 만들 수 없다")
    void rejectsBlankUrl() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                null, ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 0.95, BURST_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                " ", ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 0.95, BURST_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("connect-timeout 이 없거나 0 이면 만들 수 없다")
    void rejectsInvalidConnectTimeout() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", null, ONE_SECOND, 100, BASELINE_RTT, 0.95, BURST_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", Duration.ZERO, ONE_SECOND, 100, BASELINE_RTT, 0.95, BURST_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("read-timeout 이 없거나 0 이면 만들 수 없다")
    void rejectsInvalidReadTimeout() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, null, 100, BASELINE_RTT, 0.95, BURST_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, Duration.ZERO, 100, BASELINE_RTT, 0.95, BURST_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("max-tps 가 0 이하면 만들 수 없다")
    void rejectsNonPositiveMaxTps() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 0, BASELINE_RTT, 0.95, BURST_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("baseline-rtt 가 없거나 0 이면 만들 수 없다")
    void rejectsInvalidBaselineRtt() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, null, 0.95, BURST_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, Duration.ZERO, 0.95, BURST_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("safety-margin 이 0 이하이거나 1 을 넘으면 만들 수 없다")
    void rejectsInvalidSafetyMargin() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 0.0, BURST_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 1.1, BURST_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("burst-window 가 없거나 0 이면 만들 수 없다")
    void rejectsInvalidBurstWindow() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 0.95, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 0.95, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TargetSystemProperties.class)
    static class TestConfig {
    }
}
