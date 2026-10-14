package com.hyuk.flow_control.application.port.in;

import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;

public interface DryRunUseCase {
    DryRun start(String targetSystemId, PolicyContent content, long expectedVersion, ChangeNote note);

    DryRunStatus get(String targetSystemId);

    void cancel(String targetSystemId);

    ManagedPolicy apply(String targetSystemId, ChangeNote note);
}
