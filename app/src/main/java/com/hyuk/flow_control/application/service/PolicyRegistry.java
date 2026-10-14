package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.PolicyRepository;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class PolicyRegistry implements TargetSystemPolicyReader {
    private static final Logger log = LoggerFactory.getLogger(PolicyRegistry.class);

    private final PolicyRepository repository;
    private final ObjectProvider<PolicyChangeListener> listeners;
    private final FlowControlMetrics metrics;
    private final Map<String, ManagedPolicy> current = new ConcurrentHashMap<>();
    private final Map<String, Long> reportedInvalidVersions = new ConcurrentHashMap<>();

    public PolicyRegistry(PolicyRepository repository,
                          ObjectProvider<PolicyChangeListener> listeners,
                          FlowControlMetrics metrics) {
        this.repository = repository;
        this.listeners = listeners;
        this.metrics = metrics;

        for (LoadedPolicy loaded : repository.loadAll()) {
            switch (loaded) {
                case LoadedPolicy.Valid valid -> current.put(valid.targetSystemId(), valid.policy());
                case LoadedPolicy.Invalid invalid -> reportInvalid(invalid, false);
            }
        }

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

    public ManagedPolicy policyOf(String targetSystemId) {
        ManagedPolicy policy = current.get(targetSystemId);

        if (policy == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return policy;
    }

    public Collection<ManagedPolicy> policies() {
        return List.copyOf(current.values());
    }

    public synchronized void refresh() {
        List<LoadedPolicy> loaded;
        try {
            loaded = repository.loadAll();
        } catch (RuntimeException e) {
            metrics.recordPolicyRefreshFailure();
            log.warn("정책을 다시 읽지 못했습니다. 마지막으로 읽은 정책을 계속 씁니다", e);
            return;
        }

        for (LoadedPolicy policy : loaded) {
            switch (policy) {
                case LoadedPolicy.Valid valid -> apply(valid.policy());
                case LoadedPolicy.Invalid invalid -> reportInvalid(invalid, current.containsKey(invalid.targetSystemId()));
            }
        }
    }

    private void apply(ManagedPolicy policy) {
        String targetSystemId = policy.targetSystemId();
        ManagedPolicy before = current.get(targetSystemId);
        reportedInvalidVersions.remove(targetSystemId);

        if (before == null) {
            current.put(targetSystemId, policy);
            log.info("대상 시스템 정책을 추가했습니다 : {} 버전 {}", targetSystemId, policy.version());
            notifyEach(listener -> listener.onPolicyAdded(policy), targetSystemId);
            return;
        }

        if (policy.version() <= before.version()) {
            return;
        }

        current.put(targetSystemId, policy);
        log.info("대상 시스템 정책을 바꿨습니다 : {} 버전 {} -> {}", targetSystemId, before.version(), policy.version());
        notifyEach(listener -> listener.onPolicyChanged(before, policy), targetSystemId);
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

    private void notifyEach(Consumer<PolicyChangeListener> action, String targetSystemId) {
        listeners.orderedStream().forEach(listener -> {
            try {
                action.accept(listener);
            } catch (RuntimeException e) {
                log.error("정책 변경을 반영하지 못했습니다 : {} {}", targetSystemId, listener.getClass().getSimpleName(), e);
            }
        });
    }
}
