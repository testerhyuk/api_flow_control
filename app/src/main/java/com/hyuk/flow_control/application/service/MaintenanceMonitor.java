package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.RequestIntakeControlLookup;
import com.hyuk.flow_control.application.port.out.TargetSystemClient;
import com.hyuk.flow_control.application.settings.ControlSettings;
import com.hyuk.flow_control.application.settings.MaintenanceSettings;
import com.hyuk.flow_control.domain.limit.ControlMode;
import com.hyuk.flow_control.domain.maintenance.MaintenanceSource;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Component
public class MaintenanceMonitor {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceMonitor.class);

    private final PolicyRegistry policies;
    private final RequestIntakeControlLookup intake;
    private final TargetSystemClient client;
    private final FlowControlMetrics metrics;
    private final Duration probeInterval;
    private final boolean enabled;
    private final Executor probes;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public MaintenanceMonitor(
            PolicyRegistry policies,
            RequestIntakeControlLookup intake,
            TargetSystemClient client,
            FlowControlMetrics metrics,
            MaintenanceSettings maintenanceSettings,
            ControlSettings controlSettings) {
        this(policies, intake, client, metrics, maintenanceSettings, controlSettings,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    MaintenanceMonitor(
            PolicyRegistry policies,
            RequestIntakeControlLookup intake,
            TargetSystemClient client,
            FlowControlMetrics metrics,
            MaintenanceSettings maintenanceSettings,
            ControlSettings controlSettings,
            Executor probes) {
        this.policies = policies;
        this.intake = intake;
        this.client = client;
        this.metrics = metrics;
        this.probeInterval = maintenanceSettings.probeInterval();
        this.enabled = controlSettings.controlMode() == ControlMode.ADAPTIVE;
        this.probes = probes;
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

                if (window.update(scheduled)) {
                    ended.add(targetSystemId);
                    resume(targetSystemId);
                    log.info("대상 시스템 점검이 끝나 요청 읽기를 다시 시작합니다 : {}", targetSystemId);
                } else if (window.isActive()) {
                    pause(targetSystemId);
                    if (window.claimProbe(now)) {
                        probes.execute(() -> probe(targetSystemId, window));
                    }
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

        if (windowOf(targetSystemId).startByResponse(Instant.now().plus(probeInterval))) {
            log.info("대상 시스템이 점검 응답을 보내 요청 읽기를 멈추고 {} 마다 점검 확인용 주소로 확인합니다 : {}",
                    probeInterval, targetSystemId);
        }
        pause(targetSystemId);
    }

    private void probe(String targetSystemId, Window window) {
        boolean underMaintenance = false;

        try {
            OutcomeKind kind = client.probe(targetSystemId);
            underMaintenance = kind == OutcomeKind.UNDER_MAINTENANCE;
            log.info("대상 시스템 점검 확인 : {} {}", targetSystemId, kind);
        } catch (RuntimeException e) {
            log.warn("대상 시스템 점검 확인 호출 실패, 점검이 끝난 것으로 봅니다 : {}", targetSystemId, e);
        } finally {
            window.probed(underMaintenance, Instant.now().plus(probeInterval));
        }
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
        intake.find().ifPresent(control -> control.pause(targetSystemId));
    }

    private void resume(String targetSystemId) {
        intake.find().ifPresent(control -> control.resume(targetSystemId));
    }

    private static final class Window {
        private final String targetSystemId;
        private final FlowControlMetrics metrics;
        private boolean scheduled;
        private boolean byResponse;
        private Instant nextProbeAt;
        private boolean probing;
        private boolean activeAtLastTick;

        private Window(String targetSystemId, FlowControlMetrics metrics) {
            this.targetSystemId = targetSystemId;
            this.metrics = metrics;
        }

        private synchronized boolean update(boolean scheduledNow) {
            if (scheduledNow && !scheduled) {
                metrics.recordMaintenanceStarted(targetSystemId, MaintenanceSource.SCHEDULE);
                log.info("대상 시스템 점검 시간이 시작돼 요청 읽기를 멈춥니다 : {}", targetSystemId);
            }
            scheduled = scheduledNow;

            boolean wasActive = activeAtLastTick;
            activeAtLastTick = isActive();
            return wasActive && !activeAtLastTick;
        }

        private synchronized boolean startByResponse(Instant firstProbeAt) {
            if (byResponse) {
                return false;
            }

            metrics.recordMaintenanceStarted(targetSystemId, MaintenanceSource.RESPONSE);
            byResponse = true;
            nextProbeAt = firstProbeAt;
            return true;
        }

        private synchronized boolean claimProbe(Instant now) {
            if (!byResponse || scheduled || probing || now.isBefore(nextProbeAt)) {
                return false;
            }

            probing = true;
            return true;
        }

        private synchronized void probed(boolean underMaintenance, Instant nextAt) {
            probing = false;
            if (underMaintenance) {
                nextProbeAt = nextAt;
            } else {
                byResponse = false;
            }
        }

        private synchronized boolean isActive() {
            return scheduled || byResponse;
        }

        private synchronized boolean isActive(MaintenanceSource source) {
            return switch (source) {
                case SCHEDULE -> scheduled;
                case RESPONSE -> byResponse;
            };
        }
    }
}
