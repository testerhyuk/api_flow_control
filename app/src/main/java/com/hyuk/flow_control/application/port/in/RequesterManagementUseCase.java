package com.hyuk.flow_control.application.port.in;

import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.requester.Requester;
import com.hyuk.flow_control.domain.requester.RequesterHistoryEntry;

import java.util.List;

public interface RequesterManagementUseCase {
    List<Requester> list();

    Requester get(String requesterId);

    Requester register(String requesterId, List<String> targetSystemIds, ChangeNote note);

    Requester changeEnabled(String requesterId, boolean enabled, long expectedVersion, ChangeNote note);

    Requester addTarget(String requesterId, String targetSystemId, long expectedVersion, ChangeNote note);

    Requester removeTarget(String requesterId, String targetSystemId, long expectedVersion, ChangeNote note);

    List<RequesterHistoryEntry> history(String requesterId);
}
