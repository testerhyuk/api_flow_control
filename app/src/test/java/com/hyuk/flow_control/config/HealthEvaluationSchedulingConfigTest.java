package com.hyuk.flow_control.config;

import com.hyuk.flow_control.application.service.HealthCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class HealthEvaluationSchedulingConfigTest {

    @Test
    @DisplayName("evaluation-interval 마다 판정 주기를 반복해서 실행한다")
    void runsRepeatedly() {
        HealthCycle cycle = mock(HealthCycle.class);

        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, HealthEvaluationSchedulingConfig.class)
                .withBean(HealthCycle.class, () -> cycle)
                .withPropertyValues(
                        "flow-control.health.window=10s",
                        "flow-control.health.evaluation-interval=50ms",
                        "flow-control.health.minimum-calls=50",
                        "flow-control.health.degraded-failure-rate=0.10",
                        "flow-control.health.critical-failure-rate=0.50",
                        "flow-control.health.degraded-latency-multiplier=2.0",
                        "flow-control.health.recovery-duration=30s")
                .run(context -> verify(cycle, timeout(2000).atLeast(3)).tick());
    }

    @Test
    @DisplayName("등록한 작업을 실행하면 판정 주기를 한 번 돈다")
    void registersCycle() {
        HealthCycle cycle = mock(HealthCycle.class);
        HealthProperties properties = new HealthProperties(
                Duration.ofSeconds(10), Duration.ofSeconds(1), 50, 0.10, 0.50, 2.0, Duration.ofSeconds(30));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        new HealthEvaluationSchedulingConfig(cycle, properties).configureTasks(registrar);
        registrar.getFixedRateTaskList().getFirst().getRunnable().run();

        verify(cycle).tick();
    }

    @Configuration
    @EnableConfigurationProperties(HealthProperties.class)
    static class PropertiesConfig {
    }
}
