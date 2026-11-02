package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.ResponseJudge;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunReport;
import com.hyuk.flow_control.domain.health.HealthState;
import com.hyuk.flow_control.domain.health.HealthStatus;
import com.hyuk.flow_control.domain.health.HealthRules;
import com.hyuk.flow_control.domain.health.HealthThresholds;
import com.hyuk.flow_control.domain.health.LowTrafficRules;
import com.hyuk.flow_control.domain.health.HealthMode;
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
    private static final LowTrafficRules LOW_TRAFFIC = new LowTrafficRules(3, 3, 3, 5);
    private static final RateAdjustmentRule RATE_RULE = new RateAdjustmentRule(0.5, 10, 1.0);

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
    @DisplayName("새 한도였다면 막혔을 호출의 결과는 상태 판정에서 빼고, 뺀 건수를 알 수 없는 부분에 적는다")
    void excludesBlockedCallsFromJudgment() {
        ShadowEvaluation evaluation = evaluation(limits(100, 150), 95.0);
        completeMany(evaluation, OutcomeKind.SUCCESS, 10, NOW);
        for (int i = 0; i < 10; i++) {
            evaluation.complete(response(OutcomeKind.SERVER_ERROR, 500, 150), NOW, false);
        }

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1, baselineOf(evaluation));

        DryRunReport report = evaluation.report(NOW.plusSeconds(1), HealthState.HEALTHY, 95.0);
        assertThat(evaluation.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(report.health().sampleCount()).isEqualTo(10);
        assertThat(report.unknowns()).anyMatch(text -> text.contains("막혔을 호출 10건"));
    }

    @Test
    @DisplayName("판정 기록에 들어간 호출 결과만 판정에 쓴 호출로 센다")
    void countsJudgedCalls() {
        ShadowEvaluation evaluation = evaluation(limits(100, 150), 95.0);
        completeMany(evaluation, OutcomeKind.SUCCESS, 3, NOW);
        evaluation.complete(response(OutcomeKind.UNEXPECTED_RESPONSE, 200, 150), NOW);
        evaluation.complete(response(OutcomeKind.SUCCESS, 200, 150), NOW, false);

        DryRunReport report = evaluation.report(NOW, HealthState.HEALTHY, 95.0);
        assertThat(report.judgedCalls()).isEqualTo(3);
        assertThat(report.requiredCalls()).isEqualTo(10);
        assertThat(report.isReadyToApply()).isFalse();
    }

    @Test
    @DisplayName("막혔으면 admit 이 false 를 돌려준다")
    void admitReturnsWhetherAllowed() {
        ShadowEvaluation evaluation = evaluation(limits(80, 150), 95.0);

        assertThat(evaluation.admit(0)).isTrue();
        assertThat(evaluation.admit(0)).isFalse();
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

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1,
                new BaselineEstimator.Baselines(Duration.ofMillis(100), Duration.ofMillis(100)));

        assertThat(evaluation.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(evaluation.concurrencyLimit()).isEqualTo(10);
    }

    @Test
    @DisplayName("상태는 p95 baseline 으로 판정하고, 동시 처리 한도는 평균 baseline 으로 계산한다")
    void usesP95ForJudgmentAndMeanForConcurrency() {
        ShadowEvaluation evaluation = evaluation(limits(100, 50), 95.0);
        for (int i = 0; i < 10; i++) {
            evaluation.complete(response(OutcomeKind.SUCCESS, 200, 150), NOW);
        }

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1,
                new BaselineEstimator.Baselines(Duration.ofMillis(50), Duration.ofMillis(100)));

        assertThat(evaluation.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(evaluation.concurrencyLimit()).isEqualTo(5);
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

        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1, baselineOf(evaluation), true);

        assertThat(evaluation.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(evaluation.report(NOW.plusSeconds(1), HealthState.HEALTHY, 95.0).health().timeInState())
                .containsEntry(HealthState.HEALTHY, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("호출이 적은 판정이 이어지면 저트래픽 모드로 바꾸고, 연속 실패면 새 값 기준 상태를 위험으로 내린다")
    void judgesWithLowTrafficRules() {
        ShadowEvaluation evaluation = evaluation(limits(100, 150), 95.0);
        for (int second = 1; second <= 3; second++) {
            evaluation.evaluate(NOW.plusSeconds(second), second * 1_000_000_000L, 1, baselineOf(evaluation));
        }
        HealthMode entered = evaluation.mode();

        completeMany(evaluation, OutcomeKind.SERVER_ERROR, 3, NOW.plusSeconds(3));
        evaluation.evaluate(NOW.plusSeconds(4), 4_000_000_000L, 1, baselineOf(evaluation));

        assertThat(entered).isEqualTo(HealthMode.LOW_TRAFFIC);
        assertThat(evaluation.state()).isEqualTo(HealthState.CRITICAL);
        assertThat(evaluation.rate()).isCloseTo(1.0, within(1e-9));
        assertThat(evaluation.report(NOW.plusSeconds(4), HealthState.HEALTHY, 95.0).unknowns())
                .noneMatch(text -> text.contains("저트래픽"));
    }

    @Test
    @DisplayName("새 한도에 막힌 적이 판정 구간 안에 있으면 저트래픽 모드로 바꾸지 않는다")
    void staysAdaptiveWhileBlocked() {
        ShadowEvaluation evaluation = evaluation(limits(80, 150), 95.0);
        evaluation.admit(0);
        evaluation.admit(0);

        for (int second = 1; second <= 3; second++) {
            evaluation.evaluate(NOW.plusSeconds(second), second * 1_000_000_000L, 1, baselineOf(evaluation));
        }

        assertThat(evaluation.mode()).isEqualTo(HealthMode.ADAPTIVE);
    }

    @Test
    @DisplayName("점검 일정을 바꾸면 새 일정의 점검 시간과 그 시간의 호출 수, 달라진 시간을 세고, 그 뒤의 영향만 알 수 없다고 적는다")
    void countsCandidateMaintenance() {
        ShadowEvaluation evaluation = evaluation(withMaintenance(oneTimeMaintenance(NOW, NOW.plusSeconds(3))), 95.0);

        completeMany(evaluation, OutcomeKind.SERVER_ERROR, 10, NOW.plusSeconds(1));
        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1, baselineOf(evaluation));
        evaluation.evaluate(NOW.plusSeconds(2), 2_000_000_000L, 1, baselineOf(evaluation));
        evaluation.evaluate(NOW.plusSeconds(3), 3_000_000_000L, 1, baselineOf(evaluation));

        DryRunReport report = evaluation.report(NOW.plusSeconds(3), HealthState.HEALTHY, 95.0);
        assertThat(evaluation.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(report.health().sampleCount()).isZero();
        assertThat(report.maintenance()).isEqualTo(new DryRunReport.Maintenance(
                Duration.ofSeconds(2), 10, Duration.ofSeconds(2), Duration.ZERO));
        assertThat(report.unknowns()).containsExactly(
                "새 점검 일정이었다면 점검 중에 요청을 읽지 않아 쌓였을 요청이, 점검이 끝난 뒤 어떻게 나갔을지는 알 수 없습니다");
    }

    @Test
    @DisplayName("점검 일정이 같으면 점검 계산 결과를 남기지 않는다")
    void noMaintenanceReportWhenScheduleSame() {
        assertThat(evaluation(limits(80, 150), 95.0).report(NOW, HealthState.HEALTHY, 95.0).maintenance()).isNull();
    }

    private static ManagedPolicy withMaintenance(MaintenanceSchedule schedule) {
        ManagedPolicy changed = BASE.withVersion(2);
        return new ManagedPolicy(changed.targetSystemId(), changed.version(), changed.enabled(),
                changed.connection(), changed.limits(), changed.response(), schedule);
    }

    private static MaintenanceSchedule oneTimeMaintenance(Instant from, Instant to) {
        return new MaintenanceSchedule(List.of(), List.of(new OneTimeMaintenance(
                LocalDateTime.ofInstant(from, MaintenanceSchedule.ZONE), LocalDateTime.ofInstant(to, MaintenanceSchedule.ZONE))));
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

    @Test
    @DisplayName("회복 중 좋음은 판정에 쓰는 새 호출 결과가 판정 개수만큼 쌓였을 때만 센다")
    void countsGoodOncePerBatch() {
        ShadowEvaluation evaluation = evaluation(limits(100, 150), 95.0, new HealthStatus(HealthState.RECOVERING, 1));
        completeMany(evaluation, OutcomeKind.SUCCESS, 10, NOW);
        evaluation.evaluate(NOW.plusSeconds(1), 1_000_000_000L, 1, baselineOf(evaluation));

        completeMany(evaluation, OutcomeKind.SUCCESS, 5, NOW.plusSeconds(1));
        completeMany(evaluation, OutcomeKind.UNEXPECTED_RESPONSE, 10, NOW.plusSeconds(1));
        evaluation.evaluate(NOW.plusSeconds(2), 2_000_000_000L, 1, baselineOf(evaluation));
        HealthState waiting = evaluation.state();

        completeMany(evaluation, OutcomeKind.SUCCESS, 5, NOW.plusSeconds(2));
        evaluation.evaluate(NOW.plusSeconds(3), 3_000_000_000L, 1, baselineOf(evaluation));

        assertThat(waiting).isEqualTo(HealthState.RECOVERING);
        assertThat(evaluation.state()).isEqualTo(HealthState.HEALTHY);
    }

    private void completeMany(ShadowEvaluation evaluation, OutcomeKind kind, int count, Instant at) {
        for (int i = 0; i < count; i++) {
            evaluation.complete(response(kind, 200, 150), at);
        }
    }

    private ShadowEvaluation evaluation(ManagedPolicy candidate, double actualRate) {
        return evaluation(candidate, actualRate, HealthStatus.initial());
    }

    private ShadowEvaluation evaluation(ManagedPolicy candidate, double actualRate, HealthStatus actualStatus) {
        DryRun dryRun = new DryRun(candidate, 1, NOW, NOW.plus(Duration.ofMinutes(30)), new ChangeNote("hyuk", "test"));
        return new ShadowEvaluation(dryRun, BASE, actualStatus, actualRate, judge, RULES, LOW_TRAFFIC, RATE_RULE,
                Duration.ofSeconds(10), 10, BaselineEstimator.Baselines.of(candidate.limits()), metrics, NOW, 0L);
    }

    private static BaselineEstimator.Baselines baselineOf(ShadowEvaluation evaluation) {
        return BaselineEstimator.Baselines.of(evaluation.dryRun().candidate().limits());
    }

    private static ManagedPolicy limits(int maxTps, long baselineMillis) {
        return candidateOf(limitsOf(maxTps, baselineMillis));
    }

    private static TargetSystemPolicy limitsOf(int maxTps, long baselineMillis) {
        return new TargetSystemPolicy(maxTps, Duration.ofMillis(baselineMillis), Duration.ofMillis(baselineMillis), 0.95,
                Duration.ofMillis(10));
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
