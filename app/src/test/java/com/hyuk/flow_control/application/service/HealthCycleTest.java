package com.hyuk.flow_control.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Set;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthCycleTest {
    private final MaintenanceMonitor maintenance = mock(MaintenanceMonitor.class);
    private final BaselineEstimator baselines = mock(BaselineEstimator.class);
    private final TargetSystemHealthEvaluator evaluator = mock(TargetSystemHealthEvaluator.class);
    private final TargetSystemLimitAdjuster adjuster = mock(TargetSystemLimitAdjuster.class);
    private final DryRunMonitor dryRuns = mock(DryRunMonitor.class);
    private final HealthCycle cycle = new HealthCycle(maintenance, baselines, evaluator, adjuster, dryRuns);

    @Test
    @DisplayName("점검 확인, baseline 계산, 판정, 한도 조절, dry-run 판정 순서로 한다")
    void runsInOrder() {
        when(maintenance.tick()).thenReturn(Set.of());

        cycle.tick();

        InOrder order = inOrder(maintenance, baselines, evaluator, adjuster, dryRuns);
        order.verify(maintenance).tick();
        order.verify(baselines).refresh();
        order.verify(evaluator).evaluateAll();
        order.verify(adjuster).adjustAll();
        order.verify(dryRuns).evaluateAll();
        verify(evaluator, never()).onMaintenanceEnded("target-a");
    }

    @Test
    @DisplayName("점검이 끝난 대상은 판정 전에 회복 중으로 두고 rate 를 낮춘다")
    void handlesMaintenanceEnd() {
        when(maintenance.tick()).thenReturn(Set.of("target-a"));

        cycle.tick();

        InOrder order = inOrder(evaluator, adjuster);
        order.verify(evaluator).onMaintenanceEnded("target-a");
        order.verify(adjuster).onMaintenanceEnded("target-a");
        order.verify(evaluator).evaluateAll();
        order.verify(adjuster).adjustAll();
    }

    @Test
    @DisplayName("점검 종료 처리에서 예외가 나도 판정과 조절은 한다")
    void continuesWhenMaintenanceEndFails() {
        when(maintenance.tick()).thenReturn(Set.of("target-a"));
        doThrow(new IllegalStateException("예상하지 못한 예외")).when(evaluator).onMaintenanceEnded("target-a");

        cycle.tick();

        verify(evaluator).evaluateAll();
        verify(adjuster).adjustAll();
    }
}
