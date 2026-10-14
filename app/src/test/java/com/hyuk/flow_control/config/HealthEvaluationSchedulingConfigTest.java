package com.hyuk.flow_control.config;

import com.hyuk.flow_control.application.service.DryRunMonitor;
import com.hyuk.flow_control.application.service.TargetSystemHealthEvaluator;
import com.hyuk.flow_control.application.service.TargetSystemLimitAdjuster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class HealthEvaluationSchedulingConfigTest {

    @Test
    @DisplayName("evaluation-interval 마다 판정과 한도 조절을 반복해서 실행한다")
    void runsRepeatedly() {
        TargetSystemHealthEvaluator evaluator = mock(TargetSystemHealthEvaluator.class);
        TargetSystemLimitAdjuster adjuster = mock(TargetSystemLimitAdjuster.class);

        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, HealthEvaluationSchedulingConfig.class)
                .withBean(TargetSystemHealthEvaluator.class, () -> evaluator)
                .withBean(TargetSystemLimitAdjuster.class, () -> adjuster)
                .withBean(DryRunMonitor.class, () -> mock(DryRunMonitor.class))
                .withPropertyValues(
                        "flow-control.health.window=10s",
                        "flow-control.health.evaluation-interval=50ms",
                        "flow-control.health.minimum-calls=50",
                        "flow-control.health.degraded-failure-rate=0.10",
                        "flow-control.health.critical-failure-rate=0.50",
                        "flow-control.health.degraded-latency-multiplier=2.0",
                        "flow-control.health.recovery-duration=30s")
                .run(context -> {
                    verify(evaluator, timeout(2000).atLeast(3)).evaluateAll();
                    verify(adjuster, timeout(2000).atLeast(3)).adjustAll();
                });
    }

    @Test
    @DisplayName("한 번 실행할 때 판정, 한도 조절, dry-run 판정 순서로 한다")
    void evaluatesBeforeAdjusting() {
        TargetSystemHealthEvaluator evaluator = mock(TargetSystemHealthEvaluator.class);
        TargetSystemLimitAdjuster adjuster = mock(TargetSystemLimitAdjuster.class);
        DryRunMonitor dryRuns = mock(DryRunMonitor.class);
        HealthProperties properties = new HealthProperties(
                Duration.ofSeconds(10), Duration.ofSeconds(1), 50, 0.10, 0.50, 2.0, Duration.ofSeconds(30));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        new HealthEvaluationSchedulingConfig(evaluator, adjuster, dryRuns, properties).configureTasks(registrar);
        registrar.getFixedRateTaskList().getFirst().getRunnable().run();

        InOrder order = inOrder(evaluator, adjuster, dryRuns);
        order.verify(evaluator).evaluateAll();
        order.verify(adjuster).adjustAll();
        order.verify(dryRuns).evaluateAll();
    }

    @Configuration
    @EnableConfigurationProperties(HealthProperties.class)
    static class PropertiesConfig {
    }
}
