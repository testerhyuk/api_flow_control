package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.HealthGrade;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthStatus;
import com.hyuk.flow_control.domain.health.HealthThresholds;
import com.hyuk.flow_control.domain.health.OutcomeStatistics;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(0)
public class TargetSystemHealthEvaluator implements PolicyChangeListener {
    private static final Logger log = LoggerFactory.getLogger(TargetSystemHealthEvaluator.class);

    private final TargetSystemOutcomeRecorder recorder;
    private final TargetSystemPolicyReader policyReader;
    private final HealthThresholds thresholds;
    private final FlowControlMetrics metrics;
    private final Map<String, HealthStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, HealthSnapshot> snapshots = new ConcurrentHashMap<>();
    private final int requiredGoodCount;

    public TargetSystemHealthEvaluator(
            TargetSystemOutcomeRecorder recorder,
            TargetSystemPolicyReader policyReader,
            HealthProperties healthProperties,
            FlowControlMetrics metrics) {
        this.recorder = recorder;
        this.policyReader = policyReader;
        this.thresholds = thresholdsOf(healthProperties);
        this.metrics = metrics;

        policyReader.readAll().keySet().forEach(targetSystemId -> {
            statuses.put(targetSystemId, HealthStatus.initial());
            snapshots.put(targetSystemId, HealthSnapshot.initial());
        });

        this.requiredGoodCount = requiredGoodCountOf(healthProperties);
    }

    public static HealthThresholds thresholdsOf(HealthProperties healthProperties) {
        return new HealthThresholds(
                healthProperties.minimumCalls(),
                healthProperties.degradedFailureRate(),
                healthProperties.criticalFailureRate(),
                healthProperties.degradedLatencyMultiplier());
    }

    public static int requiredGoodCountOf(HealthProperties healthProperties) {
        return (int) Math.ceil(
                (double) healthProperties.recoveryDuration().toMillis()
                        / healthProperties.evaluationInterval().toMillis());
    }

    @Override
    public void onPolicyAdded(ManagedPolicy policy) {
        statuses.putIfAbsent(policy.targetSystemId(), HealthStatus.initial());
        snapshots.putIfAbsent(policy.targetSystemId(), HealthSnapshot.initial());
    }

    @Override
    public void onPolicyChanged(ManagedPolicy before, ManagedPolicy after) {
    }

    public void evaluateAll() {
        statuses.keySet().forEach(targetSystemId -> {
            try {
                evaluate(targetSystemId);
            } catch (RuntimeException e) {
                log.warn("대상 시스템 상태 판정 실패 : {}", targetSystemId, e);
            }
        });
    }

    public HealthSnapshot snapshotOf(String targetSystemId) {
        HealthSnapshot snapshot = snapshots.get(targetSystemId);

        if (snapshot == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return snapshot;
    }

    public HealthStatus statusOf(String targetSystemId) {
        HealthStatus status = statuses.get(targetSystemId);

        if (status == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return status;
    }

    void evaluate(String targetSystemId) {
        OutcomeStatistics statistics = OutcomeStatistics.from(recorder.recentOutcomes(targetSystemId));
        Duration baselineRtt = policyReader.read(targetSystemId).baselineRtt();

        HealthStatus current = statuses.get(targetSystemId);
        Optional<HealthGrade> grade = thresholds.gradeOf(statistics, baselineRtt);
        HealthStatus next = grade.isPresent() ? current.next(grade.get(), requiredGoodCount) : current;

        statuses.put(targetSystemId, next);
        snapshots.put(targetSystemId, new HealthSnapshot(
                next.state(),
                statistics.sampleCount(),
                statistics.failureRate(),
                statistics.p95(),
                Instant.now()));

        if (next.state() != current.state()) {
            metrics.recordStateTransition(targetSystemId, current.state(), next.state());
            log.info("대상 시스템 상태 변경 : {} {} -> {}", targetSystemId, current.state(), next.state());
        }
    }
}
