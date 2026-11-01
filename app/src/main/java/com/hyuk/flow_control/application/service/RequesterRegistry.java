package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.RequesterChangeListener;
import com.hyuk.flow_control.application.port.out.RequesterRepository;
import com.hyuk.flow_control.domain.requester.Requester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RequesterRegistry {
    private static final Logger log = LoggerFactory.getLogger(RequesterRegistry.class);

    private final RequesterRepository repository;
    private final ObjectProvider<RequesterChangeListener> listeners;
    private final Map<String, Requester> current = new ConcurrentHashMap<>();

    public RequesterRegistry(RequesterRepository repository,
                             ObjectProvider<RequesterChangeListener> listeners) {
        this.repository = repository;
        this.listeners = listeners;

        repository.loadAll().forEach(requester -> current.put(requester.requesterId(), requester));
    }

    public Collection<Requester> requesters() {
        return List.copyOf(current.values());
    }

    public List<String> requesterIdsOf(String targetSystemId) {
        return current.values().stream()
                .filter(requester -> requester.canCall(targetSystemId))
                .map(Requester::requesterId)
                .sorted()
                .toList();
    }

    public List<String> enabledRequesterIdsOf(String targetSystemId) {
        return current.values().stream()
                .filter(requester -> requester.enabled() && requester.canCall(targetSystemId))
                .map(Requester::requesterId)
                .sorted()
                .toList();
    }

    public synchronized void refresh() {
        List<Requester> loaded;
        try {
            loaded = repository.loadAll();
        } catch (RuntimeException e) {
            log.warn("요청자를 다시 읽지 못했습니다. 마지막으로 읽은 요청자를 계속 씁니다", e);
            return;
        }

        loaded.forEach(this::apply);
    }

    private void apply(Requester requester) {
        Requester before = current.get(requester.requesterId());

        if (before != null && requester.version() <= before.version()) {
            return;
        }

        current.put(requester.requesterId(), requester);
        log.info("요청자를 반영했습니다 : {} 버전 {} (외부 서버 {}, 사용 {})", requester.requesterId(),
                requester.version(), requester.targetSystemIds(), requester.enabled());

        listeners.orderedStream().forEach(listener -> {
            try {
                listener.onRequesterChanged(before, requester);
            } catch (RuntimeException e) {
                log.error("요청자 변경을 반영하지 못했습니다 : {} {}",
                        requester.requesterId(), listener.getClass().getSimpleName(), e);
            }
        });
    }
}
