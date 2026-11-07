package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.PolicyQuery;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.PolicyChangeListeners;
import com.hyuk.flow_control.application.port.out.PolicyRepository;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PolicyRegistry implements TargetSystemPolicyReader, PolicyQuery {
    private static final Logger log = LoggerFactory.getLogger(PolicyRegistry.class);

    private final PolicyRepository repository;
    private final PolicyChangeListeners listeners;
    private final FlowControlMetrics metrics;
    private final Map<String, ManagedPolicy> current = new ConcurrentHashMap<>();
    private final Map<String, Long> reportedInvalidVersions = new ConcurrentHashMap<>();
    private final Map<String, Map<PolicyChangeListener, Change>> failedChanges = new HashMap<>();
    private final Set<String> removed = new HashSet<>();
    private final Set<String> reportedTargets = new HashSet<>();
    private final Set<String> registeredTargets = new HashSet<>();

    public PolicyRegistry(PolicyRepository repository,
                          PolicyChangeListeners listeners,
                          FlowControlMetrics metrics) {
        this.repository = repository;
        this.listeners = listeners;
        this.metrics = metrics;

        List<LoadedPolicy> loadedAtStart = repository.loadAll();
        for (LoadedPolicy loaded : loadedAtStart) {
            switch (loaded) {
                case LoadedPolicy.Valid valid -> {
                    current.put(valid.targetSystemId(), valid.policy());
                    warnIfNoMaintenanceCode(valid.policy());
                }
                case LoadedPolicy.Invalid invalid -> reportInvalid(invalid, false);
            }
        }
        reportLatestInvalid(loadedAtStart);

        if (current.isEmpty()) {
            log.warn("DB 에 올바른 대상 시스템 정책이 하나도 없습니다");
        }
    }

    @Override
    public TargetSystemPolicy read(String targetSystemId) {
        return policyOf(targetSystemId).limits();
    }

    @Override
    public Map<String, TargetSystemPolicy> readAll() {
        Map<String, TargetSystemPolicy> limits = new HashMap<>();
        current.forEach((targetSystemId, policy) -> limits.put(targetSystemId, policy.limits()));
        return Map.copyOf(limits);
    }

    @Override
    public ManagedPolicy policyOf(String targetSystemId) {
        ManagedPolicy policy = current.get(targetSystemId);

        if (policy == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return policy;
    }

    @Override
    public Collection<ManagedPolicy> policies() {
        return List.copyOf(current.values());
    }

    public synchronized void refresh() {
        Set<String> notified = new HashSet<>();
        List<LoadedPolicy> loaded;
        try {
            loaded = repository.loadAll();
        } catch (RuntimeException e) {
            metrics.recordPolicyRefreshFailure();
            log.warn("정책을 다시 읽지 못했습니다. 마지막으로 읽은 정책을 계속 씁니다", e);
            retryFailed(notified);
            return;
        }

        if (loaded.isEmpty() && !current.isEmpty()) {
            metrics.recordPolicyRefreshFailure();
            log.warn("다시 읽은 정책이 하나도 없어서 무시합니다. 마지막으로 읽은 정책을 계속 씁니다");
            retryFailed(notified);
            return;
        }

        Set<String> present = new HashSet<>();
        for (LoadedPolicy policy : loaded) {
            switch (policy) {
                case LoadedPolicy.Valid valid -> {
                    present.add(valid.targetSystemId());
                    apply(valid.policy(), notified);
                }
                case LoadedPolicy.Invalid invalid -> {
                    present.add(invalid.targetSystemId());
                    reportInvalid(invalid, current.containsKey(invalid.targetSystemId()));
                }
            }
        }
        disableRemoved(present, notified);
        reportLatestInvalid(loaded);
        registerNewTargets(loaded);
        retryFailed(notified);
    }

    // 정책을 받는 곳들이 이 빈에 기대므로 생성자에서는 그 목록을 꺼낼 수 없다. 그래서 첫 refresh 부터 등록한다.
    private void registerNewTargets(List<LoadedPolicy> loaded) {
        for (LoadedPolicy policy : loaded) {
            if (registeredTargets.add(policy.targetSystemId())) {
                metrics.registerTarget(policy.targetSystemId(), listenerNames());
            }
        }
    }

    private void reportLatestInvalid(List<LoadedPolicy> loaded) {
        Set<String> present = new HashSet<>();
        for (LoadedPolicy policy : loaded) {
            present.add(policy.targetSystemId());
            metrics.recordLatestPolicyInvalid(policy.targetSystemId(), policy instanceof LoadedPolicy.Invalid);
        }
        reportedTargets.stream()
                .filter(targetSystemId -> !present.contains(targetSystemId))
                .forEach(targetSystemId -> metrics.recordLatestPolicyInvalid(targetSystemId, false));
        reportedTargets.clear();
        reportedTargets.addAll(present);
    }

    private List<String> listenerNames() {
        return listeners.all().stream().map(listeners::nameOf).toList();
    }


    private void disableRemoved(Set<String> present, Set<String> notified) {
        List.copyOf(current.keySet()).stream()
                .filter(targetSystemId -> !present.contains(targetSystemId) && !removed.contains(targetSystemId))
                .forEach(targetSystemId -> {
                    ManagedPolicy before = current.get(targetSystemId);
                    ManagedPolicy disabled = before.withEnabled(false);
                    current.put(targetSystemId, disabled);
                    removed.add(targetSystemId);
                    log.warn("DB 에서 정책이 사라져서 끈 것으로 처리합니다. 다시 넣으면 이어서 처리합니다 : {} 버전 {}",
                            targetSystemId, before.version());
                    notified.add(targetSystemId);
                    notifyEach(targetSystemId, new Change.Changed(before, disabled));
                });
    }

    private static void warnIfNoMaintenanceCode(ManagedPolicy policy) {
        if (!policy.response().hasMaintenanceCode()) {
            log.warn("응답 규칙에 점검(UNDER_MAINTENANCE) 코드가 없어서 점검 응답을 알아채지 못합니다. 점검 일정으로만 점검을 압니다 : {} 버전 {}",
                    policy.targetSystemId(), policy.version());
        }
    }

    private void apply(ManagedPolicy policy, Set<String> notified) {
        String targetSystemId = policy.targetSystemId();
        ManagedPolicy before = current.get(targetSystemId);
        reportedInvalidVersions.remove(targetSystemId);

        if (before == null) {
            current.put(targetSystemId, policy);
            log.info("대상 시스템 정책을 추가했습니다 : {} 버전 {}", targetSystemId, policy.version());
            warnIfNoMaintenanceCode(policy);
            notified.add(targetSystemId);
            notifyEach(targetSystemId, new Change.Added(policy));
            return;
        }

        if (removed.remove(targetSystemId)) {
            log.warn("DB 에서 사라졌던 정책이 다시 나타나서 반영합니다 : {} 버전 {}", targetSystemId, policy.version());
        } else if (policy.version() <= before.version()) {
            return;
        }

        current.put(targetSystemId, policy);
        log.info("대상 시스템 정책을 바꿨습니다 : {} 버전 {} -> {}", targetSystemId, before.version(), policy.version());
        warnIfNoMaintenanceCode(policy);
        notified.add(targetSystemId);
        notifyEach(targetSystemId, new Change.Changed(before, policy));
    }

    private void reportInvalid(LoadedPolicy.Invalid invalid, boolean keepingLast) {
        Long reported = reportedInvalidVersions.put(invalid.targetSystemId(), invalid.version());
        if (reported != null && reported == invalid.version()) {
            return;
        }

        metrics.recordInvalidPolicy(invalid.targetSystemId());
        if (keepingLast) {
            log.warn("잘못된 정책이라 반영하지 않고 마지막 정책을 계속 씁니다 : {} 버전 {} ({})",
                    invalid.targetSystemId(), invalid.version(), invalid.error());
        } else {
            log.warn("잘못된 정책이라 이 대상 시스템은 처리하지 않습니다 : {} 버전 {} ({})",
                    invalid.targetSystemId(), invalid.version(), invalid.error());
        }
    }

    private void notifyEach(String targetSystemId, Change change) {
        Map<PolicyChangeListener, Change> failed = failedChanges.getOrDefault(targetSystemId, Map.of());
        listeners.all().forEach(listener -> {
            Change last = failed.get(listener);
            deliver(targetSystemId, listener, last == null ? change : last.mergedWith(change), false);
        });
    }

    private void retryFailed(Set<String> notified) {
        List.copyOf(failedChanges.keySet()).stream()
                .filter(targetSystemId -> !notified.contains(targetSystemId))
                .forEach(targetSystemId -> listeners.all().forEach(listener -> {
                    Change failed = failedChanges.getOrDefault(targetSystemId, Map.of()).get(listener);
                    if (failed != null) {
                        deliver(targetSystemId, listener, failed, true);
                    }
                }));
    }

    private void deliver(String targetSystemId, PolicyChangeListener listener, Change change, boolean retry) {
        String name = listeners.nameOf(listener);
        try {
            change.deliverTo(listener);
        } catch (RuntimeException e) {
            failedChanges.computeIfAbsent(targetSystemId, id -> new HashMap<>()).put(listener, change);
            metrics.recordPolicyApplyFailure(targetSystemId, name);
            if (!retry) {
                log.error("정책 변경을 반영하지 못했습니다. 다음 읽기 때 다시 알립니다 : {} {} 버전 {}",
                        targetSystemId, name, change.version(), e);
            }
            return;
        }

        Map<PolicyChangeListener, Change> failed = failedChanges.get(targetSystemId);
        if (failed != null && failed.remove(listener) != null && failed.isEmpty()) {
            failedChanges.remove(targetSystemId);
        }
        if (retry) {
            log.info("정책 변경을 다시 알려서 반영했습니다 : {} {} 버전 {}", targetSystemId, name, change.version());
        }
    }

    private sealed interface Change {
        void deliverTo(PolicyChangeListener listener);

        ManagedPolicy latest();

        default long version() {
            return latest().version();
        }

        default Change mergedWith(Change next) {
            return switch (this) {
                case Added added -> new Added(next.latest());
                case Changed changed -> new Changed(changed.before(), next.latest());
            };
        }

        record Added(ManagedPolicy latest) implements Change {
            @Override
            public void deliverTo(PolicyChangeListener listener) {
                listener.onPolicyAdded(latest);
            }
        }

        record Changed(ManagedPolicy before, ManagedPolicy latest) implements Change {
            @Override
            public void deliverTo(PolicyChangeListener listener) {
                listener.onPolicyChanged(before, latest);
            }
        }
    }
}
