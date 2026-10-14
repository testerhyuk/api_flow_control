package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.RequestIntakeControl;
import com.hyuk.flow_control.config.ControlProperties;
import com.hyuk.flow_control.config.MaintenanceProperties;
import com.hyuk.flow_control.domain.limit.ControlMode;
import com.hyuk.flow_control.domain.maintenance.MaintenanceSchedule;
import com.hyuk.flow_control.domain.maintenance.OneTimeMaintenance;
import com.hyuk.flow_control.domain.maintenance.MaintenanceSource;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MaintenanceMonitorTest {
    private static final String TARGET = "target-a";
    private static final OutboundResult MAINTENANCE =
            new OutboundResult(OutcomeKind.UNDER_MAINTENANCE, Duration.ofMillis(20), null, null, 503, null);

    private PolicyRegistry policies;
    private RequestIntakeControl intake;
    private FlowControlMetrics metrics;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        policies = mock(PolicyRegistry.class);
        intake = mock(RequestIntakeControl.class);
        metrics = mock(FlowControlMetrics.class);
        when(policies.policies()).thenReturn(List.of(Policies.policy(TARGET)));
    }

    @Test
    @DisplayName("점검 응답이 오면 바로 요청 읽기를 멈추고, 그 응답만 판정에서 뺀다")
    void pausesOnMaintenanceResponse() {
        MaintenanceMonitor monitor = monitor(ControlMode.ADAPTIVE, Duration.ofSeconds(30));

        monitor.onResult(TARGET, MAINTENANCE);

        assertThat(monitor.isActive(TARGET)).isTrue();
        assertThat(monitor.isActive(TARGET, MaintenanceSource.RESPONSE)).isTrue();
        assertThat(monitor.excludes(TARGET, OutcomeKind.UNDER_MAINTENANCE)).isTrue();
        assertThat(monitor.excludes(TARGET, OutcomeKind.TIMEOUT)).isFalse();
        verify(intake).pause(TARGET);
        verify(metrics).recordMaintenanceStarted(TARGET, MaintenanceSource.RESPONSE);
    }

    @Test
    @DisplayName("Retry-After 가 없으면 probe-interval 뒤에 다시 읽고 점검이 끝난 것으로 알린다")
    void resumesAfterProbeInterval() throws InterruptedException {
        MaintenanceMonitor monitor = monitor(ControlMode.ADAPTIVE, Duration.ofMillis(100));
        monitor.onResult(TARGET, MAINTENANCE);

        assertThat(monitor.tick()).isEmpty();
        Thread.sleep(150);

        assertThat(monitor.tick()).containsExactly(TARGET);
        assertThat(monitor.isActive(TARGET)).isFalse();
        verify(intake).resume(TARGET);
    }

    @Test
    @DisplayName("Retry-After 가 있으면 그 시각까지 멈춘다")
    void usesRetryAfter() throws InterruptedException {
        MaintenanceMonitor monitor = monitor(ControlMode.ADAPTIVE, Duration.ofMillis(10));
        monitor.onResult(TARGET, new OutboundResult(OutcomeKind.UNDER_MAINTENANCE, Duration.ofMillis(20),
                Instant.now().plusSeconds(60), null, 503, null));

        Thread.sleep(50);

        assertThat(monitor.tick()).isEmpty();
        assertThat(monitor.isActive(TARGET)).isTrue();
    }

    @Test
    @DisplayName("점검 시간표 안이면 멈추고, 그동안 받은 응답은 모두 판정에서 뺀다")
    void pausesBySchedule() {
        LocalDateTime now = LocalDateTime.now(MaintenanceSchedule.ZONE);
        ManagedPolicy policy = withSchedule(new OneTimeMaintenance(now.minusMinutes(1), now.plusMinutes(10)));
        when(policies.policies()).thenReturn(List.of(policy));
        MaintenanceMonitor monitor = monitor(ControlMode.ADAPTIVE, Duration.ofSeconds(30));

        assertThat(monitor.tick()).isEmpty();

        assertThat(monitor.isActive(TARGET, MaintenanceSource.SCHEDULE)).isTrue();
        assertThat(monitor.excludes(TARGET, OutcomeKind.TIMEOUT)).isTrue();
        verify(intake).pause(TARGET);
        verify(metrics).recordMaintenanceStarted(TARGET, MaintenanceSource.SCHEDULE);
    }

    @Test
    @DisplayName("점검 시간표가 끝나면 다시 읽고 점검이 끝난 것으로 알린다")
    void resumesWhenScheduleEnds() {
        LocalDateTime now = LocalDateTime.now(MaintenanceSchedule.ZONE);
        ManagedPolicy during = withSchedule(new OneTimeMaintenance(now.minusMinutes(1), now.plusMinutes(10)));
        when(policies.policies()).thenReturn(List.of(during), List.of(Policies.policy(TARGET)));
        MaintenanceMonitor monitor = monitor(ControlMode.ADAPTIVE, Duration.ofSeconds(30));
        monitor.tick();

        assertThat(monitor.tick()).containsExactly(TARGET);
        verify(intake).resume(TARGET);
    }

    @Test
    @DisplayName("적응형이 아니면 점검을 알아내지도 멈추지도 않는다")
    void disabledOutsideAdaptive() {
        LocalDateTime now = LocalDateTime.now(MaintenanceSchedule.ZONE);
        when(policies.policies()).thenReturn(List.of(
                withSchedule(new OneTimeMaintenance(now.minusMinutes(1), now.plusMinutes(10)))));
        MaintenanceMonitor monitor = monitor(ControlMode.FIXED, Duration.ofSeconds(30));

        monitor.onResult(TARGET, MAINTENANCE);

        assertThat(monitor.tick()).isEmpty();
        assertThat(monitor.isActive(TARGET)).isFalse();
        assertThat(monitor.excludes(TARGET, OutcomeKind.UNDER_MAINTENANCE)).isFalse();
        verifyNoInteractions(intake, metrics);
    }

    @Test
    @DisplayName("점검이 아닌 응답은 아무것도 하지 않는다")
    void ignoresOtherResults() {
        MaintenanceMonitor monitor = monitor(ControlMode.ADAPTIVE, Duration.ofSeconds(30));

        monitor.onResult(TARGET, new OutboundResult(OutcomeKind.SERVER_ERROR, Duration.ofMillis(20), null, null, 503, null));

        assertThat(monitor.isActive(TARGET)).isFalse();
        verify(intake, never()).pause(any());
    }

    private ManagedPolicy withSchedule(OneTimeMaintenance window) {
        ManagedPolicy base = Policies.policy(TARGET);
        return new ManagedPolicy(base.targetSystemId(), base.version(), base.enabled(), base.connection(),
                base.limits(), base.response(), new MaintenanceSchedule(List.of(), List.of(window)));
    }

    @SuppressWarnings("unchecked")
    private MaintenanceMonitor monitor(ControlMode mode, Duration probeInterval) {
        ObjectProvider<RequestIntakeControl> provider = mock(ObjectProvider.class);
        doAnswer(invocation -> {
            ((Consumer<RequestIntakeControl>) invocation.getArgument(0)).accept(intake);
            return null;
        }).when(provider).ifAvailable(any());

        return new MaintenanceMonitor(policies, provider, metrics,
                new MaintenanceProperties(probeInterval, 0.5), new ControlProperties(mode));
    }
}
