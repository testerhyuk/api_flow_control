package com.hyuk.flow_control.config;

import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final Map<String, OutcomeKind> STATUS_CODES = Map.of(
            "2xx", OutcomeKind.SUCCESS,
            "429", OutcomeKind.TOO_MANY_REQUESTS,
            "5xx", OutcomeKind.SERVER_ERROR);
    private static final TargetSystemProperties.Response RESPONSE =
            new TargetSystemProperties.Response(STATUS_CODES, null, null);

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
                        "flow-control.target-systems.target-a.burst-window=10ms",
                        "flow-control.target-systems.target-a.response.status-codes.2xx=SUCCESS",
                        "flow-control.target-systems.target-a.response.status-codes.429=TOO_MANY_REQUESTS",
                        "flow-control.target-systems.target-a.response.status-codes.5xx=SERVER_ERROR",
                        "flow-control.target-systems.target-a.response.result-field=/header/resultCode",
                        "flow-control.target-systems.target-a.response.result-codes.0000=SUCCESS",
                        "flow-control.target-systems.target-a.response.result-codes.E100=BUSINESS_REJECTED")
                .run(context -> {
                    TargetSystemProperties properties = context.getBean(TargetSystemProperties.class);

                    assertThat(properties.targetSystems()).isEqualTo(Map.of(
                            "target-a", new TargetSystemProperties.TargetSystem(
                                    "http://localhost:9001", Duration.ofSeconds(1), Duration.ofSeconds(3),
                                    100, Duration.ofMillis(100), 0.95, Duration.ofMillis(10),
                                    new TargetSystemProperties.Response(
                                            STATUS_CODES,
                                            "/header/resultCode",
                                            Map.of("0000", OutcomeKind.SUCCESS,
                                                    "E100", OutcomeKind.BUSINESS_REJECTED)))
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
                        "flow-control.target-systems.target-a.burst-window=10ms",
                        "flow-control.target-systems.target-a.response.status-codes.2xx=SUCCESS")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("상태 코드 표를 적지 않으면 앱이 뜨지 않는다")
    void failsToStartWithoutStatusCodes() {
        contextRunner
                .withPropertyValues(
                        "flow-control.target-systems.target-a.url=http://localhost:9001",
                        "flow-control.target-systems.target-a.connect-timeout=1s",
                        "flow-control.target-systems.target-a.read-timeout=3s",
                        "flow-control.target-systems.target-a.max-tps=100",
                        "flow-control.target-systems.target-a.baseline-rtt=100ms",
                        "flow-control.target-systems.target-a.safety-margin=0.95",
                        "flow-control.target-systems.target-a.burst-window=10ms")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("결과 이름이 아닌 값을 적으면 앱이 뜨지 않는다")
    void failsToStartWithUnknownOutcomeName() {
        contextRunner
                .withPropertyValues(
                        "flow-control.target-systems.target-a.url=http://localhost:9001",
                        "flow-control.target-systems.target-a.connect-timeout=1s",
                        "flow-control.target-systems.target-a.read-timeout=3s",
                        "flow-control.target-systems.target-a.max-tps=100",
                        "flow-control.target-systems.target-a.baseline-rtt=100ms",
                        "flow-control.target-systems.target-a.safety-margin=0.95",
                        "flow-control.target-systems.target-a.burst-window=10ms",
                        "flow-control.target-systems.target-a.response.status-codes.2xx=OK")
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
                null, ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 0.95, BURST_WINDOW, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                " ", ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 0.95, BURST_WINDOW, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("connect-timeout 이 없거나 0 이면 만들 수 없다")
    void rejectsInvalidConnectTimeout() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", null, ONE_SECOND, 100, BASELINE_RTT, 0.95, BURST_WINDOW, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", Duration.ZERO, ONE_SECOND, 100, BASELINE_RTT, 0.95, BURST_WINDOW, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("read-timeout 이 없거나 0 이면 만들 수 없다")
    void rejectsInvalidReadTimeout() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, null, 100, BASELINE_RTT, 0.95, BURST_WINDOW, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, Duration.ZERO, 100, BASELINE_RTT, 0.95, BURST_WINDOW, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("max-tps 가 0 이하면 만들 수 없다")
    void rejectsNonPositiveMaxTps() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 0, BASELINE_RTT, 0.95, BURST_WINDOW, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("baseline-rtt 가 없거나 0 이면 만들 수 없다")
    void rejectsInvalidBaselineRtt() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, null, 0.95, BURST_WINDOW, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, Duration.ZERO, 0.95, BURST_WINDOW, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("safety-margin 이 0 이하이거나 1 을 넘으면 만들 수 없다")
    void rejectsInvalidSafetyMargin() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 0.0, BURST_WINDOW, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 1.1, BURST_WINDOW, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("burst-window 가 없거나 0 이면 만들 수 없다")
    void rejectsInvalidBurstWindow() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 0.95, null, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 0.95, Duration.ZERO, RESPONSE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("response 가 없으면 만들 수 없다")
    void rejectsMissingResponse() {
        assertThatThrownBy(() -> new TargetSystemProperties.TargetSystem(
                "http://localhost:9001", ONE_SECOND, ONE_SECOND, 100, BASELINE_RTT, 0.95, BURST_WINDOW, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상태 코드 표가 없거나 비어 있으면 만들 수 없다")
    void rejectsMissingStatusCodes() {
        assertThatThrownBy(() -> new TargetSystemProperties.Response(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.Response(Map.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"20", "2000", "600", "6xx", "x00", "2XX", "abc"})
    @DisplayName("상태 코드 형식이 세 자리 숫자나 백 단위 묶음이 아니면 만들 수 없다")
    void rejectsInvalidStatusCode(String code) {
        assertThatThrownBy(() -> new TargetSystemProperties.Response(
                Map.of(code, OutcomeKind.SUCCESS), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결과 코드 위치와 결과 코드 표는 함께 적어야 한다")
    void requiresResultFieldAndCodesTogether() {
        assertThatThrownBy(() -> new TargetSystemProperties.Response(
                STATUS_CODES, "/resultCode", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSystemProperties.Response(
                STATUS_CODES, null, Map.of("0000", OutcomeKind.SUCCESS)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결과 코드 위치가 / 로 시작하지 않으면 만들 수 없다")
    void rejectsResultFieldWithoutSlash() {
        assertThatThrownBy(() -> new TargetSystemProperties.Response(
                STATUS_CODES, "resultCode", Map.of("0000", OutcomeKind.SUCCESS)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TargetSystemProperties.class)
    static class TestConfig {
    }
}
