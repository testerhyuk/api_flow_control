package com.hyuk.flow_control.application.port.in;

import com.hyuk.flow_control.domain.health.BaselineBound;
import com.hyuk.flow_control.domain.health.BaselineKind;
import com.hyuk.flow_control.domain.health.Baselines;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.HealthThresholds;
import com.hyuk.flow_control.domain.maintenance.MaintenanceSource;

import java.time.Duration;
import java.util.Optional;

/** 외부 서버별로 지금 판정, 한도, 점검, dry-run 이 어떤 상태인지 읽는다. */
public interface FlowControlStatusQuery {
    HealthThresholds activeThresholds();

    HealthSnapshot healthOf(String targetSystemId);

    Baselines baselinesOf(String targetSystemId);

    int baselineSampleCount(String targetSystemId);

    Optional<Duration> learnedBaseline(String targetSystemId, BaselineKind kind);

    Optional<BaselineBound> clampedBaseline(String targetSystemId, BaselineKind kind);

    boolean maintenanceActive(String targetSystemId, MaintenanceSource source);

    double appliedRate(String targetSystemId);

    double proposedRate(String targetSystemId);

    int servers(String targetSystemId);

    int concurrencyLimit(String targetSystemId);

    int inFlight(String targetSystemId);

    boolean dryRunActive(String targetSystemId);

    Optional<HealthState> dryRunState(String targetSystemId);

    Optional<Double> dryRunRate(String targetSystemId);

    Optional<Integer> dryRunConcurrencyLimit(String targetSystemId);

    Optional<Duration> dryRunOverlap(String targetSystemId);

    Optional<Long> dryRunJudgedCalls(String targetSystemId);

    int dryRunRequiredCalls();
}
