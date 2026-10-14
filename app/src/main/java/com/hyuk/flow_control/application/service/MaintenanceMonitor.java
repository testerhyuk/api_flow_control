package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.RequestIntakeControl;
import com.hyuk.flow_control.config.ControlProperties;
import com.hyuk.flow_control.config.MaintenanceProperties;
import com.hyuk.flow_control.domain.limit.ControlMode;
import com.hyuk.flow_control.domain.maintenance.MaintenanceSource;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MaintenanceMonitor {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceMonitor.class);

    private final PolicyRegistry policies;
    private final ObjectProvider<RequestIntakeControl> intake;
    private final FlowControlMetrics metrics;
    private final Duration probeInterval;
    private final boolean enabled;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public MaintenanceMonitor(
            PolicyRegistry policies,
            ObjectProvider<RequestIntakeControl> intake,
            FlowControlMetrics metrics,
            MaintenanceProperties maintenanceProperties,
            ControlProperties controlProperties) {
        this.policies = policies;
        this.intake = intake;
        this.metrics = metrics;
        this.probeInterval = maintenanceProperties.probeInterval();
        this.enabled = controlProperties.controlMode() == ControlMode.ADAPTIVE;
    }

    public Set<String> tick() {
        if (!enabled) {
            return Set.of();
        }

        Instant now = Instant.now();
        Set<String> ended = new HashSet<>();

        policies.policies().forEach(policy -> {
            String targetSystemId = policy.targetSystemId();
            try {
                Window window = windowOf(targetSystemId);
                boolean scheduled = policy.maintenance().isActiveAt(now);

                if (window.update(scheduled, now)) {
                    ended.add(targetSystemId);
                    resume(targetSystemId);
                    log.info("대상 시스템 점검이 끝나 요청 읽기를 다시 시작합니다 : {}", targetSystemId);
                } else if (window.isActive()) {
                    pause(targetSystemId);
                }
            } catch (RuntimeException e) {
                log.warn("대상 시스템 점검 확인 실패 : {}", targetSystemId, e);
            }
        });

        return ended;
    }

    public void onResult(String targetSystemId, OutboundResult result) {
        if (!enabled || result.kind() != OutcomeKind.UNDER_MAINTENANCE) {
            return;
        }

        Instant now = Instant.now();
        Instant until = result.retryAfter() != null && result.retryAfter().isAfter(now)
                ? result.retryAfter()
                : now.plus(probeInterval);

        if (windowOf(targetSystemId).extendByResponse(until)) {
            log.info("대상 시스템이 점검 응답을 보내 {} 까지 요청 읽기를 멈춥니다 : {}", until, targetSystemId);
        }
        pause(targetSystemId);
    }

    public boolean isActive(String targetSystemId) {
        Window window = windows.get(targetSystemId);
        return window != null && window.isActive();
    }

    public boolean isActive(String targetSystemId, MaintenanceSource source) {
        Window window = windows.get(targetSystemId);
        return window != null && window.isActive(source);
    }

    public boolean excludes(String targetSystemId, OutcomeKind kind) {
        if (!enabled) {
            return false;
        }
        if (kind == OutcomeKind.UNDER_MAINTENANCE) {
            return true;
        }
        return isActive(targetSystemId, MaintenanceSource.SCHEDULE);
    }

    private Window windowOf(String targetSystemId) {
        return windows.computeIfAbsent(targetSystemId, id -> new Window(id, metrics));
    }

    private void pause(String targetSystemId) {
        intake.ifAvailable(control -> control.pause(targetSystemId));
    }

    private void resume(String targetSystemId) {
        intake.ifAvailable(control -> control.resume(targetSystemId));
    }

    private static final class Window {
        private final String targetSystemId;
        private final FlowControlMetrics metrics;
        private boolean scheduled;
        private Instant responseUntil;

        private Window(String targetSystemId, FlowControlMetrics metrics) {
            this.targetSystemId = targetSystemId;
            this.metrics = metrics;
        }

        private synchronized boolean update(boolean scheduledNow, Instant now) {
            boolean wasActive = isActive();

            if (scheduledNow && !scheduled) {
                metrics.recordMaintenanceStarted(targetSystemId, MaintenanceSource.SCHEDULE);
                log.info("대상 시스템 점검 시간이 시작돼 요청 읽기를 멈춥니다 : {}", targetSystemId);
            }
            scheduled = scheduledNow;

            if (responseUntil != null && !now.isBefore(responseUntil)) {
                responseUntil = null;
            }

            return wasActive && !isActive();
        }

        private synchronized boolean extendByResponse(Instant until) {
            boolean started = responseUntil == null;

            if (started) {
                metrics.recordMaintenanceStarted(targetSystemId, MaintenanceSource.RESPONSE);
            }
            if (started || until.isAfter(responseUntil)) {
                responseUntil = until;
            }
            return started;
        }

        private synchronized boolean isActive() {
            return scheduled || responseUntil != null;
        }

        private synchronized boolean isActive(MaintenanceSource source) {
            return switch (source) {
                case SCHEDULE -> scheduled;
                case RESPONSE -> responseUntil != null;
            };
        }
    }
}
