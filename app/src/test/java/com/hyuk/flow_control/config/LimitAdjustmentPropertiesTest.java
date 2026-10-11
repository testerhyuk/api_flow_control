package com.hyuk.flow_control.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitAdjustmentPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("설정 파일의 값이 그대로 들어온다")
    void bindsFromProperties() {
        contextRunner
                .withPropertyValues(
                        "flow-control.limit-adjustment.degraded-ratio=0.5",
                        "flow-control.limit-adjustment.recovery-step=0.05")
                .run(context -> assertThat(context.getBean(LimitAdjustmentProperties.class))
                        .isEqualTo(new LimitAdjustmentProperties(0.5, 0.05)));
    }

    @Test
    @DisplayName("설정 값이 잘못되면 앱이 뜨지 않는다")
    void failsToStartWithInvalidValue() {
        contextRunner
                .withPropertyValues(
                        "flow-control.limit-adjustment.degraded-ratio=1.0",
                        "flow-control.limit-adjustment.recovery-step=0.05")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("설정이 하나도 없으면 앱이 뜨지 않는다")
    void failsToStartWithoutProperties() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("degraded-ratio 가 0 이하이거나 1 이상이면 만들 수 없다")
    void rejectsInvalidDegradedRatio() {
        assertThatThrownBy(() -> new LimitAdjustmentProperties(0.0, 0.05))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitAdjustmentProperties(-0.1, 0.05))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitAdjustmentProperties(1.0, 0.05))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("recovery-step 은 1 까지 되고 0 이하이거나 1 을 넘으면 만들 수 없다")
    void validatesRecoveryStep() {
        assertThat(new LimitAdjustmentProperties(0.5, 1.0).recoveryStep()).isEqualTo(1.0);
        assertThatThrownBy(() -> new LimitAdjustmentProperties(0.5, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitAdjustmentProperties(0.5, 1.01))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration
    @EnableConfigurationProperties(LimitAdjustmentProperties.class)
    static class TestConfig {
    }
}
