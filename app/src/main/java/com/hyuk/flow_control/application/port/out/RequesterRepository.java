package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.requester.Requester;
import com.hyuk.flow_control.domain.requester.RequesterHistoryEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RequesterRepository {
    List<Requester> loadAll();

    Optional<Requester> load(String requesterId);

    void insert(Requester requester, ChangeNote note, Instant changedAt);

    void update(Requester requester, long expectedVersion, ChangeNote note, Instant changedAt);

    List<RequesterHistoryEntry> history(String requesterId);
}
