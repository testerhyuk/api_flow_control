package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.config.HealthProperties;
import com.hyuk.flow_control.domain.health.HealthGrade;
import com.hyuk.flow_control.domain.health.HealthSnapshot;
import com.hyuk.flow_control.domain.health.HealthStatus;
import com.hyuk.flow_control.domain.health.OutcomeStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TargetSystemHealthEvaluator {
    private static final Logger log = LoggerFactory.getLogger(TargetSystemHealthEvaluator.class);

    private final TargetSystemOutcomeRecorder recorder;
    private final TargetSystemPolicyReader policyReader;
    private final HealthProperties healthProperties;
    private final Map<String, HealthStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, HealthSnapshot> snapshots = new ConcurrentHashMap<>();
    private final int requiredGoodCount;

    public TargetSystemHealthEvaluator(
            TargetSystemOutcomeRecorder recorder,
            TargetSystemPolicyReader policyReader,
            HealthProperties healthProperties) {
        this.recorder = recorder;
        this.policyReader = policyReader;
        this.healthProperties = healthProperties;

        policyReader.readAll().keySet().forEach(targetSystemId -> {
            statuses.put(targetSystemId, HealthStatus.initial());
            snapshots.put(targetSystemId, HealthSnapshot.initial());
        });

        this.requiredGoodCount = (int) Math.ceil(
                (double) healthProperties.recoveryDuration().toMillis()
                        / healthProperties.evaluationInterval().toMillis());
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

    void evaluate(String targetSystemId) {
        OutcomeStatistics statistics = OutcomeStatistics.from(recorder.recentOutcomes(targetSystemId));
        Duration baselineRtt = policyReader.read(targetSystemId).baselineRtt();

        HealthStatus current = statuses.get(targetSystemId);
        Optional<HealthGrade> grade = gradeOf(statistics, baselineRtt);
        HealthStatus next = grade.isPresent() ? current.next(grade.get(), requiredGoodCount) : current;

        statuses.put(targetSystemId, next);
        snapshots.put(targetSystemId, new HealthSnapshot(
                next.state(),
                statistics.sampleCount(),
                statistics.failureRate(),
                statistics.p95(),
                Instant.now()));

        if (next.state() != current.state()) {
            log.info("대상 시스템 상태 변경 : {} {} -> {}", targetSystemId, current.state(), next.state());
        }
    }

    private Optional<HealthGrade> gradeOf(OutcomeStatistics statistics, Duration baselineRtt) {
        if (statistics.sampleCount() < healthProperties.minimumCalls()) {
            return Optional.empty();
        }

        double failureRate = statistics.failureRate();

        if (failureRate >= healthProperties.criticalFailureRate()) {
            return Optional.of(HealthGrade.CRITICAL);
        }
        if (failureRate >= healthProperties.degradedFailureRate() || isSlow(statistics.p95(), baselineRtt)) {
            return Optional.of(HealthGrade.DEGRADED);
        }
        return Optional.of(HealthGrade.GOOD);
    }

    private boolean isSlow(Duration p95, Duration baselineRtt) {
        if (p95 == null) {
            return false;
        }

        double limitNanos = baselineRtt.toNanos() * healthProperties.degradedLatencyMultiplier();

        return p95.toNanos() >= limitNanos;
    }
}
