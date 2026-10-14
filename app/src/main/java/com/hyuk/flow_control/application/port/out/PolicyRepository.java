package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.PolicyHistoryEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PolicyRepository {
    List<LoadedPolicy> loadAll();

    Optional<LoadedPolicy> load(String targetSystemId);

    void insert(ManagedPolicy policy, ChangeNote note, Instant changedAt);

    void update(ManagedPolicy policy, long expectedVersion, ChangeNote note, Instant changedAt);

    List<PolicyHistoryEntry> history(String targetSystemId);

    Optional<ManagedPolicy> findVersion(String targetSystemId, long version);
}
