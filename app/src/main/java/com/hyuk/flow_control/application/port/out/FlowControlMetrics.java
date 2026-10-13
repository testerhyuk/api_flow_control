package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.request.OutcomeKind;

import java.time.Duration;

public interface FlowControlMetrics {
    void recordCall(String targetSystemId, OutcomeKind kind, Duration latency);

    void recordBlockedBySemaphore(String targetSystemId);

    void recordBlockedByTokenBucket(String targetSystemId);

    void recordStateTransition(String targetSystemId, HealthState from, HealthState to);
}
