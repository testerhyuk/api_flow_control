package com.hyuk.flow_control.application.port.in;

import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.PolicyHistoryEntry;

import java.util.List;

public interface PolicyManagementUseCase {
    List<LoadedPolicy> list();

    LoadedPolicy get(String targetSystemId);

    ManagedPolicy create(String targetSystemId, PolicyContent content, ChangeNote note);

    ManagedPolicy update(String targetSystemId, PolicyContent content, long expectedVersion, ChangeNote note);

    ManagedPolicy changeEnabled(String targetSystemId, boolean enabled, long expectedVersion, ChangeNote note);

    List<PolicyHistoryEntry> history(String targetSystemId);

    ManagedPolicy rollback(String targetSystemId, long toVersion, long expectedVersion, ChangeNote note);
}
