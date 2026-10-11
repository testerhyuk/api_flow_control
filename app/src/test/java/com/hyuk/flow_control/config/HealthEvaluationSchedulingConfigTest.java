package com.hyuk.flow_control.config;

import com.hyuk.flow_control.application.service.TargetSystemHealthEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class HealthEvaluationSchedulingConfigTest {

    @Test
    @DisplayName("evaluation-interval 마다 모든 대상 판정을 반복해서 실행한다")
    void runsEvaluateAllRepeatedly() {
        TargetSystemHealthEvaluator evaluator = mock(TargetSystemHealthEvaluator.class);

        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, HealthEvaluationSchedulingConfig.class)
                .withBean(TargetSystemHealthEvaluator.class, () -> evaluator)
                .withPropertyValues(
                        "flow-control.health.window=10s",
                        "flow-control.health.evaluation-interval=50ms",
                        "flow-control.health.minimum-calls=50",
                        "flow-control.health.degraded-failure-rate=0.10",
                        "flow-control.health.critical-failure-rate=0.50",
                        "flow-control.health.degraded-latency-multiplier=2.0",
                        "flow-control.health.recovery-duration=30s")
                .run(context -> verify(evaluator, timeout(2000).atLeast(3)).evaluateAll());
    }

    @Configuration
    @EnableConfigurationProperties(HealthProperties.class)
    static class PropertiesConfig {
    }
}
