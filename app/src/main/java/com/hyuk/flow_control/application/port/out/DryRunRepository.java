package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DryRunRepository {
    List<DryRun> loadAll();

    Optional<DryRun> find(String targetSystemId);

    void insert(DryRun dryRun);

    boolean delete(String targetSystemId);

    List<DryRun> deleteStale(Instant now);

    void saveResult(DryRunResult result);
}
