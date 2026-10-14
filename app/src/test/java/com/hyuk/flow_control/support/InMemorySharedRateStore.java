package com.hyuk.flow_control.support;

import com.hyuk.flow_control.application.port.out.SharedRateStore;
import com.hyuk.flow_control.application.port.out.SharedRateStoreUnavailableException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySharedRateStore implements SharedRateStore {
    private static final String SELF = "self";

    private final Map<String, Map<String, Double>> proposals = new ConcurrentHashMap<>();
    private final Map<String, Double> applied = new ConcurrentHashMap<>();
    private volatile boolean available = true;
    private volatile boolean attempted = true;

    @Override
    public List<Double> exchangeProposals(String targetSystemId, double proposedRate) {
        failIfUnavailable();
        proposalsOf(targetSystemId).put(SELF, proposedRate);
        return List.copyOf(proposalsOf(targetSystemId).values());
    }

    @Override
    public Optional<Double> appliedRate(String targetSystemId) {
        failIfUnavailable();
        return Optional.ofNullable(applied.get(targetSystemId));
    }

    @Override
    public void saveAppliedRate(String targetSystemId, double rate) {
        failIfUnavailable();
        applied.put(targetSystemId, rate);
    }

    public void proposeFromOtherServer(String targetSystemId, String serverId, double rate) {
        proposalsOf(targetSystemId).put(serverId, rate);
    }

    public void expireOtherServer(String targetSystemId, String serverId) {
        proposalsOf(targetSystemId).remove(serverId);
    }

    public void storeAppliedRate(String targetSystemId, double rate) {
        applied.put(targetSystemId, rate);
    }

    public Optional<Double> storedAppliedRate(String targetSystemId) {
        return Optional.ofNullable(applied.get(targetSystemId));
    }

    public void becomeUnavailable(boolean attempted) {
        this.available = false;
        this.attempted = attempted;
    }

    public void becomeAvailable() {
        this.available = true;
    }

    private Map<String, Double> proposalsOf(String targetSystemId) {
        return proposals.computeIfAbsent(targetSystemId, id -> new ConcurrentHashMap<>());
    }

    private void failIfUnavailable() {
        if (!available) {
            throw new SharedRateStoreUnavailableException("Redis 를 쓸 수 없습니다", attempted, attempted, null);
        }
    }
}
