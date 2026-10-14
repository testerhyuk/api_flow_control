package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.ResponseJudge;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunReport;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.HealthStatus;
import com.hyuk.flow_control.domain.health.HealthRules;
import com.hyuk.flow_control.domain.health.HealthThresholds;
import com.hyuk.flow_control.domain.limit.RateAdjustmentRule;
import com.hyuk.flow_control.domain.maintenance.MaintenanceSchedule;
import com.hyuk.flow_control.domain.maintenance.OneTimeMaintenance;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.ConnectionSettings;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.ResponseRuleSet;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShadowEvaluationTest {
    private static final String TARGET = "target-a";
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final ManagedPolicy BASE = Policies.policy(TARGET, 1);
    private static final HealthThresholds THRESHOLDS = new HealthThresholds(10, 0.1, 0.5, 2.0);
    private static final HealthRules RULES = new HealthRules(THRESHOLDS, 3, 1, false);
    private static final RateAdjustmentRule RATE_RULE = new RateAdjustmentRule(0.5, 0.05, 10, 1.0);

    private ResponseJudge judge;
    private FlowControlMetrics metrics;

    @BeforeEach
    void setUp() {
        judge = mock(ResponseJudge.class);
        metrics = mock(FlowControlMetrics.class);
    }

    @Test
    @DisplayName("새 rate 는 지금 rate 에서 시작하되 새 최대치를 넘지 않는다")
    void startsFromActualRateCappedByCandidate() {
        ShadowEvaluation lowered = evaluation(limits(80, 150), 95.0);
        ShadowEvaluation raised = evaluation(limits(120, 150), 47.5);

        assertThat(lowered.rate()).isCloseTo(76.0, within(1e-9));
        assertThat(lowered.concurrencyLimit()).isEqualTo(12);
        assertThat(raised.rate()).isCloseTo(47.5, within(1e-9));
    }

    @Test
    @DisplayName("새 rate 의 토큰이 없으면 막혔을 호출로 센다")
    void countsBlockedByTokenBucket() {
        ShadowEvaluation evaluation = evaluation(limits(80, 150), 95.0);

        evaluation.admit(0);
        evaluation.admit(0);

        DryRunReport report = evaluation.report(NOW, HealthState.HEALTHY, 95.0);
        assertThat(report.observedCalls()).isEqualTo(2);
        assertThat(report.limits().wouldBlockByTokenBucket()).isEqualTo(1);
        assertThat(report.limits().wouldBlockBySemaphore()).isZero();
        verify(metrics).recordDryRunBlockedByTokenBucket(TARGET);
    }

    @Test
    @DisplayName("진행 중인 실제 호출이 새 동시 처리 한도를 넘으면 막혔을 호출로 센다")
    void countsBlockedBySemaphore() {
        ShadowEvaluation evaluation = evaluation(limits(10, 100), 95.0);

        evaluation.admit(0);
        evaluation.admit(2_000_000_000L);
        evaluation.release();
        evaluation.release();
        evaluation.admit(4_000_000_000L);

        DryRunReport report = evaluation.report(NOW, HealthState.HEALTHY, 95.0);
        assertThat(evaluation.concurrencyLimit()).isEqualTo(1);
        assertThat(report.limits().wouldBlockBySemaphore()).isEqualTo(1);
        assertThat(report.limits().wouldBlockByTokenBucket()).isZero();
    }

    @Test
    @DisplayName("지금 한도 때문에 기다린 호출 수를 센다")
    void countsWaitedCalls() {
        ShadowEvaluation evaluation = evaluation(limits(120, 150), 95.0);

        evaluation.recordWaited();
        evaluation.recordWaited();

        assertThat(evaluation.report(NOW, HealthState.HEALTHY, 95.0).limits().waitedByCurrentLimit()).isEqualTo(2);
        verify(metrics, times(2)).recordDryRunWaited(TARGET);
    }

    @Test
    @DisplayName("응답 규칙이 바뀌면 새 규칙으로 다시 판단하고 달라진 판단을 센다")
    void rejudgesWithCandidateRules() {
        ResponseRuleSet rules = new ResponseRuleSet(Map.of("2xx", OutcomeKind.SUCCESS, "422", OutcomeKind.SERVER_ERROR),
                null, null);
        when(judge.judge(rules, 422, "{}")).thenReturn(OutcomeKind.SERVER_ERROR);
        ShadowEvaluation evaluation = evaluation(Policies.withResponse(candidateOf(BASE.limits()), rules), 95.0);

        evaluation.complete(response(OutcomeKind.BUSINESS_REJECTED, 422, 100), NOW);

        DryRunReport report = evaluation.report(NOW, HealthState.HEALTHY, 95.0);
        assertThat(report.judgmentChanges()).containsExactly(
                new DryRunReport.JudgmentChange(OutcomeKind.BUSINESS_REJECTED, OutcomeKind.SERVER_ERROR, 1));
        verify(metrics).recordDryRunJudgmentChange(TARGET, OutcomeKind.BUSINESS_REJECTED, OutcomeKind.SERVER_ERROR);
    }

    @Test
    @DisplayName("응답 규칙이 같으면 다시 판단하지 않는다")
    void skipsRejudgeWithSameRules() {
        ShadowEvaluation evaluation = evaluation(limits(80, 150), 95.0);

        evaluation.complete(response(OutcomeKind.SUCCESS, 200, 100), NOW);

        verifyNoInteractions(judge);
    }

    @Test
    @DisplayName("응답 없이 끝난 호출은 다시 판단하지 않는다")
    void skipsRejudgeWithoutResponse() {
        ResponseRuleSet rules = new ResponseRuleSet(Map.of("2xx", OutcomeKind.SUCCESS), null, null);
        ShadowEvaluation evaluation = evaluation(Policies.withResponse(candidateOf(BASE.limits()), rules), 95.0);

        evaluation.complete(new OutboundResult(OutcomeKind.CONNECTION_FAILED, Duration.ofMillis(5), null, null, null, null), NOW);

        verify(judge, never()).judge(any(), anyInt(), any());
    }

    @Test
    @DisplayName("새 read-timeout 이 더 짧으면 그보다 오래 걸린 호출을 timeout 났을 호출로 센다")
    void countsWouldTimeoutWithShorterTimeout() {
        ShadowEvaluation evaluation = evaluation(withReadTimeout(Duration.ofSeconds(1)), 95.0);

        evaluation.complete(response(OutcomeKind.SUCCESS, 200, 1500), NOW);
        evaluation.complete(response(OutcomeKind.SUCCESS, 200, 900), NOW);
        evaluation.complete(new OutboundResult(OutcomeKind.TIMEOUT, Duration.ofSeconds(3), null, null, null, null), NOW);

        DryRunReport report = evaluation.report(NOW, HealthState.HEALTHY, 95.0);
        assertThat(report.timeouts().wouldTimeout()).isEqualTo(1);
        assertThat(report.timeouts().actualTimeoutsWithUnknownResult()).isZero();
        verify(metrics).recordDryRunTimeout(TARGET);
    }

    @Test
    @DisplayName("새 read-timeout 이 더 길면 지금 timeout 난 호출을 결과를 알 수 없는 호출로 센다")
    void countsUnknownTimeoutsWithLongerTimeout() {
        ShadowEvaluation evaluation = evaluation(withReadTimeout(Duration.ofSeconds(5)), 95.0);

        evaluation.complete(new OutboundResult(OutcomeKind.TIMEOUT, Duration.ofSeconds(3), null, null, null, null), NOW);

        DryRunReport report = evaluation.report(NOW, HealthState.HEALTHY, 95.0);
        assertThat(report.timeouts().actualTimeoutsWithUnknownResult()).isEqualTo(1);
        assertThat(report.unknowns()).anyMatch(text -> text.contains("read-timeout"));
        verify(metrics).recordDryRunUnknownTimeout(TARGET);
    }

    @Test
    @DisplayName("새 baseline-rtt 기준으로 상태를 따로 판정하고, 상태에 따라 새 rate 를 조절한다")
    void evaluatesStateWithCandidateBaseline() {
        ShadowEvaluation evaluation = evaluation(limits(100, 50), 95.0);
        for (int i = 0; i < 10; i++) {
            evaluation.complete(response(OutcomeKind.SUCCESS, 200, 150), NOW);
        }

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1, baselineOf(evaluation));

        DryRunReport report = evaluation.report(NOW.plusSeconds(1), HealthState.HEALTHY, 95.0);
        assertThat(report.health().state()).isEqualTo(HealthState.DEGRADED);
        assertThat(report.health().actualState()).isEqualTo(HealthState.HEALTHY);
        assertThat(report.health().transitions()).isEqualTo(1);
        assertThat(evaluation.rate()).isCloseTo(47.5, within(1e-9));
        verify(metrics).recordDryRunStateTransition(TARGET, HealthState.HEALTHY, HealthState.DEGRADED);
    }

    @Test
    @DisplayName("배운 baseline 을 넘겨받으면 새 정책 값 대신 그 값으로 판정한다")
    void usesGivenBaseline() {
        ShadowEvaluation evaluation = evaluation(limits(100, 50), 95.0);
        for (int i = 0; i < 10; i++) {
            evaluation.complete(response(OutcomeKind.SUCCESS, 200, 150), NOW);
        }

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1, Duration.ofMillis(100));

        assertThat(evaluation.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(evaluation.concurrencyLimit()).isEqualTo(10);
    }

    @Test
    @DisplayName("같은 호출이라도 지금 baseline-rtt 면 HEALTHY 로 남는다")
    void staysHealthyWithCurrentBaseline() {
        ShadowEvaluation evaluation = evaluation(limits(100, 150), 95.0);
        for (int i = 0; i < 10; i++) {
            evaluation.complete(response(OutcomeKind.SUCCESS, 200, 150), NOW);
        }

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1, baselineOf(evaluation));

        assertThat(evaluation.state()).isEqualTo(HealthState.HEALTHY);
    }

    @Test
    @DisplayName("새 read-timeout 으로 timeout 났을 호출은 상태 판정에서 실패로 센다")
    void wouldTimeoutCountsAsFailure() {
        ShadowEvaluation evaluation = evaluation(withReadTimeout(Duration.ofMillis(100)), 95.0);
        for (int i = 0; i < 10; i++) {
            evaluation.complete(response(OutcomeKind.SUCCESS, 200, 150), NOW);
        }

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1, baselineOf(evaluation));

        assertThat(evaluation.state()).isEqualTo(HealthState.CRITICAL);
    }

    @Test
    @DisplayName("window 보다 오래된 호출은 판정에서 뺀다")
    void dropsOutcomesOutsideWindow() {
        ShadowEvaluation evaluation = evaluation(limits(100, 50), 95.0);
        for (int i = 0; i < 10; i++) {
            evaluation.complete(response(OutcomeKind.SUCCESS, 200, 150), NOW);
        }

        evaluation.evaluate(NOW.plusSeconds(11), 11_000_000_000L, 1, baselineOf(evaluation));

        assertThat(evaluation.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(evaluation.report(NOW.plusSeconds(11), HealthState.HEALTHY, 95.0).health().sampleCount()).isZero();
    }

    @Test
    @DisplayName("상태별로 머문 시간을 쌓는다")
    void accumulatesTimeInState() {
        ShadowEvaluation evaluation = evaluation(limits(100, 50), 95.0);
        for (int i = 0; i < 10; i++) {
            evaluation.complete(response(OutcomeKind.SUCCESS, 200, 150), NOW);
        }

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1, baselineOf(evaluation));
        DryRunReport report = evaluation.report(NOW.plusSeconds(4), HealthState.HEALTHY, 95.0);

        assertThat(report.health().timeInState())
                .containsEntry(HealthState.HEALTHY, Duration.ofSeconds(1))
                .containsEntry(HealthState.DEGRADED, Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("한도를 올리거나 connect-timeout 을 바꾸면 알 수 없는 부분을 결과에 적는다")
    void listsUnknowns() {
        ManagedPolicy candidate = Policies.withConnection(candidateOf(limitsOf(120, 150)),
                new ConnectionSettings(BASE.connection().url(), Duration.ofSeconds(2), BASE.connection().readTimeout()));

        DryRunReport report = evaluation(candidate, 95.0).report(NOW, HealthState.HEALTHY, 95.0);

        assertThat(report.unknowns()).hasSize(2)
                .anyMatch(text -> text.contains("한도"))
                .anyMatch(text -> text.contains("connect-timeout"));
    }

    @Test
    @DisplayName("점검 중에는 새 값 기준 상태를 판정하지 않는다")
    void skipsJudgmentDuringMaintenance() {
        ShadowEvaluation evaluation = evaluation(limits(100, 50), 95.0);
        for (int i = 0; i < 10; i++) {
            evaluation.complete(response(OutcomeKind.SUCCESS, 200, 150), NOW);
        }

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1, baselineOf(evaluation), true, false);

        assertThat(evaluation.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(evaluation.report(NOW.plusSeconds(1), HealthState.HEALTHY, 95.0).health().timeInState())
                .containsEntry(HealthState.HEALTHY, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("실제 판정이 저트래픽 모드였던 시간을 알 수 없는 부분에 적는다")
    void listsLowTrafficTime() {
        ShadowEvaluation evaluation = evaluation(limits(80, 150), 95.0);

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1, baselineOf(evaluation), false, true);
        evaluation.evaluate(NOW.plusSeconds(3), 3_000_000_000L, 1, baselineOf(evaluation), false, true);

        assertThat(evaluation.report(NOW.plusSeconds(3), HealthState.HEALTHY, 95.0).unknowns())
                .anyMatch(text -> text.contains("저트래픽 모드였던 3초"));
    }

    @Test
    @DisplayName("점검 시간표를 바꾸면 그 효과는 확인할 수 없다고 적는다")
    void listsMaintenanceScheduleChange() {
        ManagedPolicy changed = BASE.withVersion(2);
        ManagedPolicy candidate = new ManagedPolicy(changed.targetSystemId(), changed.version(), changed.enabled(),
                changed.connection(), changed.limits(), changed.response(), new MaintenanceSchedule(List.of(),
                List.of(new OneTimeMaintenance(LocalDateTime.of(2026, 10, 20, 1, 0), LocalDateTime.of(2026, 10, 20, 5, 0)))));

        assertThat(evaluation(candidate, 95.0).report(NOW, HealthState.HEALTHY, 95.0).unknowns())
                .containsExactly("점검 시간표를 바꾼 효과는 확인할 수 없습니다");
    }

    @Test
    @DisplayName("한도를 낮추기만 하면 알 수 없는 부분이 없다")
    void noUnknownsWhenLowering() {
        assertThat(evaluation(limits(80, 150), 95.0).report(NOW, HealthState.HEALTHY, 95.0).unknowns()).isEmpty();
    }

    @Test
    @DisplayName("다른 서버와 겹친 시간을 쌓고, 알 수 없는 부분에 적는다")
    void accumulatesOverlap() {
        ShadowEvaluation evaluation = evaluation(limits(80, 150), 95.0);

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 2, baselineOf(evaluation));
        evaluation.evaluate(NOW.plusSeconds(2), 2_000_000_000L, 1, baselineOf(evaluation));
        evaluation.evaluate(NOW.plusSeconds(3), 3_000_000_000L, 2, baselineOf(evaluation));

        DryRunReport report = evaluation.report(NOW.plusSeconds(3), HealthState.HEALTHY, 95.0);
        assertThat(report.overlap()).isEqualTo(Duration.ofSeconds(2));
        assertThat(report.unknowns()).anyMatch(text -> text.contains("겹친 2초"));
    }

    @Test
    @DisplayName("겹친 적이 없으면 겹친 시간은 0 이고 알 수 없는 부분에 적지 않는다")
    void noOverlapWhenAlone() {
        ShadowEvaluation evaluation = evaluation(limits(80, 150), 95.0);

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1, baselineOf(evaluation));

        DryRunReport report = evaluation.report(NOW.plusSeconds(1), HealthState.HEALTHY, 95.0);
        assertThat(report.overlap()).isZero();
        assertThat(report.unknowns()).noneMatch(text -> text.contains("겹친"));
    }

    private ShadowEvaluation evaluation(ManagedPolicy candidate, double actualRate) {
        DryRun dryRun = new DryRun(candidate, 1, NOW, NOW.plus(Duration.ofMinutes(30)), new ChangeNote("hyuk", "test"));
        return new ShadowEvaluation(dryRun, BASE, HealthStatus.initial(), actualRate, judge, RULES, RATE_RULE,
                Duration.ofSeconds(10), 10, candidate.limits().baselineRtt(), metrics, NOW, 0L);
    }

    private static Duration baselineOf(ShadowEvaluation evaluation) {
        return evaluation.dryRun().candidate().limits().baselineRtt();
    }

    private static ManagedPolicy limits(int maxTps, long baselineMillis) {
        return candidateOf(limitsOf(maxTps, baselineMillis));
    }

    private static TargetSystemPolicy limitsOf(int maxTps, long baselineMillis) {
        return new TargetSystemPolicy(maxTps, Duration.ofMillis(baselineMillis), 0.95, Duration.ofMillis(10));
    }

    private static ManagedPolicy candidateOf(TargetSystemPolicy limits) {
        return Policies.withLimits(BASE.withVersion(2), limits);
    }

    private static ManagedPolicy withReadTimeout(Duration readTimeout) {
        return Policies.withConnection(BASE.withVersion(2),
                new ConnectionSettings(BASE.connection().url(), BASE.connection().connectTimeout(), readTimeout));
    }

    private static OutboundResult response(OutcomeKind kind, int status, long latencyMillis) {
        return new OutboundResult(kind, Duration.ofMillis(latencyMillis), null, "{}", status, null);
    }
}
