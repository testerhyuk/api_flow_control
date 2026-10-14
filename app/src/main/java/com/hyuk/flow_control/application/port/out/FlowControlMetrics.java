package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.limit.LimitStore;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.retry.DeadLetterReason;
import com.hyuk.flow_control.domain.retry.RetryStage;

import java.time.Duration;

public interface FlowControlMetrics {
    void recordCall(String targetSystemId, OutcomeKind kind, Duration latency);

    void recordBlockedBySemaphore(String targetSystemId);

    void recordBlockedByTokenBucket(String targetSystemId);

    void recordStateTransition(String targetSystemId, HealthState from, HealthState to);

    void recordIntakeWait(String targetSystemId, Duration waited);

    void recordRetryScheduled(String targetSystemId, RetryStage stage, OutcomeKind kind);

    void recordRetrySucceeded(String targetSystemId, RetryStage stage);

    void recordDeadLetter(String targetSystemId, DeadLetterReason reason);

    void recordPolicyRefreshFailure();

    void recordInvalidPolicy(String targetSystemId);

    void recordDryRunObserved(String targetSystemId);

    void recordDryRunBlockedBySemaphore(String targetSystemId);

    void recordDryRunBlockedByTokenBucket(String targetSystemId);

    void recordDryRunWaited(String targetSystemId);

    void recordDryRunJudgmentChange(String targetSystemId, OutcomeKind actual, OutcomeKind candidate);

    void recordDryRunTimeout(String targetSystemId);

    void recordDryRunUnknownTimeout(String targetSystemId);

    void recordDryRunStateTransition(String targetSystemId, HealthState from, HealthState to);

    void recordLimitStoreSwitch(LimitStore to);

    void recordRedisLatency(Duration latency);

    void recordRedisError(String operation, boolean timeout);
}
