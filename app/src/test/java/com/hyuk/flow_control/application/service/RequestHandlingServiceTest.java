package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.RequestIntakeUseCase;
import com.hyuk.flow_control.application.port.out.FailedRequestPublisher;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.ResultPublisher;
import com.hyuk.flow_control.config.ControlProperties;
import com.hyuk.flow_control.config.IntakeProperties;
import com.hyuk.flow_control.domain.expiry.ExpiryPoint;
import com.hyuk.flow_control.domain.limit.ControlMode;
import com.hyuk.flow_control.domain.request.IntakeResult;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.result.FinalOutcome;
import com.hyuk.flow_control.domain.result.RequestResult;
import com.hyuk.flow_control.domain.retry.DeadLetterReason;
import com.hyuk.flow_control.domain.retry.DeliveryContext;
import com.hyuk.flow_control.domain.retry.HandlingOutcome;
import com.hyuk.flow_control.domain.retry.RetryStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RequestHandlingServiceTest {
    private static final String TARGET = "target-a";
    private static final OutboundRequest REQUEST = new OutboundRequest("req-1", TARGET, "{}", Instant.now());
    private static final DeliveryContext FIRST = DeliveryContext.first("flow-control.requests.target-a-0@7");
    private static final DeliveryContext FROM_LOAN =
            DeliveryContext.first("flow-control.requests.target-a.loan-0@7", "loan");
    private static final Duration TTL = Duration.ofMinutes(10);

    private RequestIntakeUseCase intake;
    private FailedRequestPublisher publisher;
    private ResultPublisher results;
    private FlowControlMetrics metrics;
    private RequestHandlingService service;

    @BeforeEach
    void setUp() {
        intake = mock(RequestIntakeUseCase.class);
        publisher = mock(FailedRequestPublisher.class);
        results = mock(ResultPublisher.class);
        metrics = mock(FlowControlMetrics.class);
        service = serviceIn(ControlMode.ADAPTIVE);
    }

    private RequestHandlingService serviceIn(ControlMode mode) {
        return new RequestHandlingService(intake, publisher, results, metrics,
                new IntakeProperties(TTL, Duration.ofSeconds(5)), new ControlProperties(mode));
    }

    private void verifyOnlyAgeRecorded() {
        verify(metrics).recordRequestAge(eq(TARGET), any());
        verifyNoMoreInteractions(metrics);
    }

    @Test
    @DisplayName("성공하면 끝내고 재시도·DLQ·만료 토픽에 보내지 않는다")
    void completesOnSuccess() {
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(result(OutcomeKind.SUCCESS, null)));

        assertThat(service.handle(REQUEST, FIRST)).isEqualTo(new HandlingOutcome.Completed(OutcomeKind.SUCCESS));
        verifyNoInteractions(publisher);
        verifyOnlyAgeRecorded();
    }

    @Test
    @DisplayName("재시도에서 성공하면 그 단계의 재시도 성공을 지표로 남긴다")
    void recordsRetrySuccess() {
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(result(OutcomeKind.SUCCESS, null)));
        DeliveryContext retry = FIRST.afterFailure(Instant.now()).movedTo(RetryStage.SECOND);

        service.handle(REQUEST, retry);

        verify(metrics).recordRetrySucceeded(TARGET, RetryStage.SECOND);
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("BUSINESS_REJECTED 는 폐기하고 재시도·DLQ·만료 토픽에 보내지 않는다")
    void discardsBusinessRejected() {
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(result(OutcomeKind.BUSINESS_REJECTED, null)));

        assertThat(service.handle(REQUEST, FIRST)).isEqualTo(new HandlingOutcome.Discarded(OutcomeKind.BUSINESS_REJECTED));
        verifyNoInteractions(publisher);
        verifyOnlyAgeRecorded();
    }

    @ParameterizedTest
    @EnumSource(value = OutcomeKind.class, names = {
            "CONNECTION_FAILED", "SERVER_ERROR", "TOO_MANY_REQUESTS", "UNDER_MAINTENANCE", "TIMEOUT", "NETWORK_ERROR"})
    @DisplayName("처음 실패한 재시도 대상은 1초 단계로 보내고 실패 횟수를 1로 남긴다")
    void schedulesFirstRetry(OutcomeKind kind) {
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(result(kind, null)));

        HandlingOutcome outcome = service.handle(REQUEST, FIRST);

        assertThat(outcome).isEqualTo(new HandlingOutcome.RetryScheduled(RetryStage.FIRST, kind));
        DeliveryContext sent = capturedRetryContext(kind);
        assertThat(sent.stage()).isEqualTo(RetryStage.FIRST);
        assertThat(sent.failedAttempts()).isEqualTo(1);
        assertThat(sent.firstFailedAt()).isNotNull();
        assertThat(sent.origin()).isEqualTo(FIRST.origin());
        verify(metrics).recordRetryScheduled(TARGET, RetryStage.FIRST, kind);
    }

    @Test
    @DisplayName("재시도에서 또 실패하면 다음 단계로 보내고 처음 실패 시각은 유지한다")
    void schedulesNextStage() {
        Instant firstFailedAt = Instant.parse("2026-10-13T09:00:00Z");
        DeliveryContext secondStage = new DeliveryContext(RetryStage.SECOND, 2, firstFailedAt, firstFailedAt, "o");
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(result(OutcomeKind.TIMEOUT, null)));

        assertThat(service.handle(REQUEST, secondStage))
                .isEqualTo(new HandlingOutcome.RetryScheduled(RetryStage.THIRD, OutcomeKind.TIMEOUT));

        DeliveryContext sent = capturedRetryContext(OutcomeKind.TIMEOUT);
        assertThat(sent.stage()).isEqualTo(RetryStage.THIRD);
        assertThat(sent.failedAttempts()).isEqualTo(3);
        assertThat(sent.firstFailedAt()).isEqualTo(firstFailedAt);
    }

    @Test
    @DisplayName("마지막 단계에서도 실패하면 재시도 소진으로 DLQ 에 보낸다")
    void deadLettersWhenExhausted() {
        DeliveryContext lastStage = new DeliveryContext(RetryStage.FOURTH, 4, Instant.now(), Instant.now(), "o");
        OutboundResult failed = new OutboundResult(
                OutcomeKind.SERVER_ERROR, Duration.ofMillis(100), null, "<html>503</html>", 503, null);
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(failed));

        assertThat(service.handle(REQUEST, lastStage))
                .isEqualTo(new HandlingOutcome.DeadLettered(DeadLetterReason.RETRY_EXHAUSTED));
        verify(publisher).deadLetter(eq(REQUEST), any(), eq(DeadLetterReason.RETRY_EXHAUSTED),
                eq(failed), isNull());
        verify(publisher, never()).scheduleRetry(any(), any(), any());
        verify(metrics).recordDeadLetter(TARGET, DeadLetterReason.RETRY_EXHAUSTED);
    }

    @Test
    @DisplayName("외부 기관이 30초 뒤에 보내라고 하면 1초와 10초 단계를 건너뛰고 60초 단계로 보낸다")
    void respectsRetryAfter() {
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(
                result(OutcomeKind.TOO_MANY_REQUESTS, Instant.now().plusSeconds(30))));

        assertThat(service.handle(REQUEST, FIRST))
                .isEqualTo(new HandlingOutcome.RetryScheduled(RetryStage.THIRD, OutcomeKind.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("알려 준 대기 시간이 이미 지났으면 1초 단계로 보낸다")
    void pastRetryAfterUsesFirstStage() {
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(
                result(OutcomeKind.TOO_MANY_REQUESTS, Instant.now().minusSeconds(5))));

        assertThat(service.handle(REQUEST, FIRST))
                .isEqualTo(new HandlingOutcome.RetryScheduled(RetryStage.FIRST, OutcomeKind.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("알려 준 대기 시간이 마지막 단계보다 길면 재시도 소진으로 DLQ 에 보낸다")
    void retryAfterLongerThanLastStage() {
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(
                result(OutcomeKind.TOO_MANY_REQUESTS, Instant.now().plusSeconds(3600))));

        assertThat(service.handle(REQUEST, FIRST))
                .isEqualTo(new HandlingOutcome.DeadLettered(DeadLetterReason.RETRY_EXHAUSTED));
    }

    @Test
    @DisplayName("자동 조절에서 점검 응답이면 Retry-After 를 보지 않고 다음 단계로 보낸다")
    void maintenanceIgnoresRetryAfter() {
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(
                result(OutcomeKind.UNDER_MAINTENANCE, Instant.now().plusSeconds(3600))));

        assertThat(service.handle(REQUEST, FIRST))
                .isEqualTo(new HandlingOutcome.RetryScheduled(RetryStage.FIRST, OutcomeKind.UNDER_MAINTENANCE));
    }

    @Test
    @DisplayName("자동 조절이 아니면 점검 응답도 Retry-After 로 단계를 고른다")
    void maintenanceUsesRetryAfterOutsideAdaptive() {
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(
                result(OutcomeKind.UNDER_MAINTENANCE, Instant.now().plusSeconds(3600))));

        assertThat(serviceIn(ControlMode.FIXED).handle(REQUEST, FIRST))
                .isEqualTo(new HandlingOutcome.DeadLettered(DeadLetterReason.RETRY_EXHAUSTED));
    }

    @ParameterizedTest
    @EnumSource(value = OutcomeKind.class, names = {"INVALID_REQUEST", "UNEXPECTED_RESPONSE"})
    @DisplayName("DLQ 대상 kind 는 재시도 없이 바로 DLQ 에 보낸다")
    void deadLettersPermanentFailures(OutcomeKind kind) {
        OutboundResult failed = new OutboundResult(kind, Duration.ofMillis(100), null, "{}", 200, "E999");
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(failed));

        assertThat(service.handle(REQUEST, FIRST)).isEqualTo(new HandlingOutcome.DeadLettered(DeadLetterReason.DLQ_KIND));
        ArgumentCaptor<DeliveryContext> context = ArgumentCaptor.forClass(DeliveryContext.class);
        verify(publisher).deadLetter(eq(REQUEST), context.capture(), eq(DeadLetterReason.DLQ_KIND), eq(failed), isNull());
        assertThat(context.getValue().failedAttempts()).isEqualTo(1);
        verify(metrics).recordDeadLetter(TARGET, DeadLetterReason.DLQ_KIND);
    }

    @Test
    @DisplayName("처리 중 예외가 나면 예외 내용을 담아 DLQ 에 보낸다")
    void deadLettersProcessingError() {
        when(intake.process(eq(REQUEST), any())).thenThrow(new IllegalArgumentException("설정에 없는 대상"));

        assertThat(service.handle(REQUEST, FIRST))
                .isEqualTo(new HandlingOutcome.DeadLettered(DeadLetterReason.PROCESSING_ERROR));
        verify(publisher).deadLetter(eq(REQUEST), any(), eq(DeadLetterReason.PROCESSING_ERROR), isNull(),
                eq("IllegalArgumentException: 설정에 없는 대상"));
        verify(metrics).recordDeadLetter(TARGET, DeadLetterReason.PROCESSING_ERROR);
    }

    @Test
    @DisplayName("앱 종료로 대기가 중단되면 DLQ 에 보내지 않고 그대로 예외를 낸다")
    void rethrowsInterruption() {
        when(intake.process(eq(REQUEST), any())).thenThrow(new IntakeInterruptedException(new InterruptedException()));

        assertThatThrownBy(() -> service.handle(REQUEST, FIRST)).isInstanceOf(IntakeInterruptedException.class);
        verifyNoInteractions(publisher);
        verifyOnlyAgeRecorded();
    }

    @Test
    @DisplayName("재시도 토픽에 넣다가 실패하면 예외를 내서 처리 완료로 기록되지 않게 한다")
    void propagatesPublishFailure() {
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(result(OutcomeKind.TIMEOUT, null)));
        doThrow(new IllegalStateException("Kafka 장애")).when(publisher).scheduleRetry(any(), any(), any());

        assertThatThrownBy(() -> service.handle(REQUEST, FIRST)).isInstanceOf(IllegalStateException.class);
        verify(metrics, never()).recordRetryScheduled(any(), any(), any());
    }

    @Test
    @DisplayName("잘못된 메시지는 원래 값 그대로 DLQ 에 보낸다")
    void rejectsInvalidMessage() {
        assertThat(service.rejectInvalid(TARGET, "not-json", FIRST, "JSON 이 아닙니다"))
                .isEqualTo(new HandlingOutcome.DeadLettered(DeadLetterReason.INVALID_MESSAGE));

        ArgumentCaptor<DeliveryContext> context = ArgumentCaptor.forClass(DeliveryContext.class);
        verify(publisher).deadLetterRaw(eq(TARGET), eq("not-json"), context.capture(), eq("JSON 이 아닙니다"));
        assertThat(context.getValue().failedAttempts()).isEqualTo(1);
        verify(metrics).recordDeadLetter(TARGET, DeadLetterReason.INVALID_MESSAGE);
        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("꺼낸 순간 요청이 생성된 지 얼마나 됐는지 지표로 남긴다")
    void recordsRequestAge() {
        OutboundRequest old = new OutboundRequest("req-old", TARGET, "{}", Instant.now().minusSeconds(30));
        when(intake.process(eq(old), any())).thenReturn(new IntakeResult.Called(result(OutcomeKind.SUCCESS, null)));

        service.handle(old, FIRST);

        ArgumentCaptor<Duration> age = ArgumentCaptor.forClass(Duration.class);
        verify(metrics).recordRequestAge(eq(TARGET), age.capture());
        assertThat(age.getValue()).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("꺼낸 요청이 유효기간을 넘었으면 호출하지 않고 만료 토픽으로 보낸다")
    void expiresAtIntake() {
        OutboundRequest old = new OutboundRequest("req-old", TARGET, "{}", Instant.now().minus(Duration.ofMinutes(11)));
        DeliveryContext retry = new DeliveryContext(RetryStage.SECOND, 2, Instant.now(), Instant.now(), "o",
                OutcomeKind.TIMEOUT);

        assertThat(service.handle(old, retry)).isEqualTo(new HandlingOutcome.Expired(ExpiryPoint.INTAKE));

        ArgumentCaptor<Duration> age = ArgumentCaptor.forClass(Duration.class);
        verify(publisher).expire(eq(old), eq(retry), eq(ExpiryPoint.INTAKE), eq(OutcomeKind.TIMEOUT), age.capture());
        assertThat(age.getValue()).isGreaterThanOrEqualTo(Duration.ofMinutes(11));
        verify(metrics).recordExpired(TARGET, ExpiryPoint.INTAKE);
        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("유효기간 안이면 오래됐어도 호출한다")
    void processesWithinTtl() {
        OutboundRequest old = new OutboundRequest("req-old", TARGET, "{}", Instant.now().minus(Duration.ofMinutes(9)));
        when(intake.process(eq(old), any())).thenReturn(new IntakeResult.Called(result(OutcomeKind.SUCCESS, null)));

        assertThat(service.handle(old, FIRST)).isEqualTo(new HandlingOutcome.Completed(OutcomeKind.SUCCESS));
        verify(publisher, never()).expire(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("요청이 만들어진 시각 + 유효기간을 한도 대기의 마감 시각으로 넘긴다")
    void passesTtlDeadlineToIntake() {
        Instant createdAt = Instant.now().minusSeconds(30);
        OutboundRequest request = new OutboundRequest("req-deadline", TARGET, "{}", createdAt);
        when(intake.process(eq(request), any())).thenReturn(new IntakeResult.Called(result(OutcomeKind.SUCCESS, null)));

        service.handle(request, FIRST);

        verify(intake).process(request, createdAt.plus(TTL));
    }

    @Test
    @DisplayName("한도를 기다리다 유효기간이 지나면 호출하지 않고 만료 토픽으로 보낸다")
    void expiresWhileWaitingForLimit() {
        DeliveryContext retry = new DeliveryContext(RetryStage.SECOND, 2, Instant.now(), Instant.now(), "o",
                OutcomeKind.TIMEOUT);
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Expired(Instant.now()));

        assertThat(service.handle(REQUEST, retry)).isEqualTo(new HandlingOutcome.Expired(ExpiryPoint.LIMIT_WAIT));

        verify(publisher).expire(eq(REQUEST), eq(retry), eq(ExpiryPoint.LIMIT_WAIT), eq(OutcomeKind.TIMEOUT), any());
        verify(metrics).recordExpired(TARGET, ExpiryPoint.LIMIT_WAIT);
        verify(publisher, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    @DisplayName("다음 재시도 시각이 유효기간을 넘으면 재시도 토픽에 넣지 않고 바로 만료 토픽으로 보낸다")
    void expiresAtRetrySchedule() {
        OutboundRequest old = new OutboundRequest("req-old", TARGET, "{}",
                Instant.now().minus(Duration.ofMinutes(9)).minusSeconds(30));
        DeliveryContext secondStage = new DeliveryContext(RetryStage.SECOND, 2, Instant.now(), Instant.now(), "o");
        when(intake.process(eq(old), any())).thenReturn(new IntakeResult.Called(result(OutcomeKind.TIMEOUT, null)));

        assertThat(service.handle(old, secondStage))
                .isEqualTo(new HandlingOutcome.Expired(ExpiryPoint.RETRY_SCHEDULE));

        ArgumentCaptor<DeliveryContext> context = ArgumentCaptor.forClass(DeliveryContext.class);
        verify(publisher).expire(eq(old), context.capture(), eq(ExpiryPoint.RETRY_SCHEDULE),
                eq(OutcomeKind.TIMEOUT), any());
        assertThat(context.getValue().failedAttempts()).isEqualTo(3);
        verify(publisher, never()).scheduleRetry(any(), any(), any());
        verify(metrics).recordExpired(TARGET, ExpiryPoint.RETRY_SCHEDULE);
        verify(metrics, never()).recordRetryScheduled(any(), any(), any());
    }

    @Test
    @DisplayName("다음 재시도 시각이 유효기간 안이면 그대로 재시도 토픽에 넣는다")
    void schedulesRetryWithinTtl() {
        OutboundRequest old = new OutboundRequest("req-old", TARGET, "{}", Instant.now().minus(Duration.ofMinutes(8)));
        DeliveryContext secondStage = new DeliveryContext(RetryStage.SECOND, 2, Instant.now(), Instant.now(), "o");
        when(intake.process(eq(old), any())).thenReturn(new IntakeResult.Called(result(OutcomeKind.TIMEOUT, null)));

        assertThat(service.handle(old, secondStage))
                .isEqualTo(new HandlingOutcome.RetryScheduled(RetryStage.THIRD, OutcomeKind.TIMEOUT));
    }

    @Test
    @DisplayName("성공하면 외부 서버 응답과 시도 횟수를 요청자의 결과로 보낸다")
    void reportsCompleted() {
        OutboundResult success = new OutboundResult(
                OutcomeKind.SUCCESS, Duration.ofMillis(100), null, "{\"grade\":1}", 200, "0000");
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(success));
        DeliveryContext retry = FROM_LOAN.afterFailure(Instant.now()).movedTo(RetryStage.FIRST);

        service.handle(REQUEST, retry);

        RequestResult sent = capturedResult("loan");
        assertThat(sent.requestId()).isEqualTo("req-1");
        assertThat(sent.targetSystemId()).isEqualTo(TARGET);
        assertThat(sent.outcome()).isEqualTo(FinalOutcome.COMPLETED);
        assertThat(sent.kind()).isEqualTo(OutcomeKind.SUCCESS);
        assertThat(sent.statusCode()).isEqualTo(200);
        assertThat(sent.resultCode()).isEqualTo("0000");
        assertThat(sent.responseBody()).isEqualTo("{\"grade\":1}");
        assertThat(sent.attempts()).isEqualTo(2);
        assertThat(sent.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("폐기하면 폐기 결과를 보낸다")
    void reportsDiscarded() {
        when(intake.process(eq(REQUEST), any()))
                .thenReturn(new IntakeResult.Called(result(OutcomeKind.BUSINESS_REJECTED, null)));

        service.handle(REQUEST, FROM_LOAN);

        RequestResult sent = capturedResult("loan");
        assertThat(sent.outcome()).isEqualTo(FinalOutcome.DISCARDED);
        assertThat(sent.kind()).isEqualTo(OutcomeKind.BUSINESS_REJECTED);
        assertThat(sent.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("재시도로 넘길 때는 결과를 보내지 않는다")
    void doesNotReportRetry() {
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(result(OutcomeKind.TIMEOUT, null)));

        service.handle(REQUEST, FROM_LOAN);

        verifyNoInteractions(results);
    }

    @Test
    @DisplayName("재시도를 다 쓰면 실패 보관 이유와 마지막 응답을 결과로 보낸다")
    void reportsDeadLettered() {
        DeliveryContext lastStage = new DeliveryContext(RetryStage.FOURTH, 4, Instant.now(), Instant.now(), "o",
                OutcomeKind.TIMEOUT, "loan");
        OutboundResult failed = new OutboundResult(
                OutcomeKind.SERVER_ERROR, Duration.ofMillis(100), null, "<html>503</html>", 503, null);
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(failed));

        service.handle(REQUEST, lastStage);

        RequestResult sent = capturedResult("loan");
        assertThat(sent.outcome()).isEqualTo(FinalOutcome.DEAD_LETTERED);
        assertThat(sent.deadLetterReason()).isEqualTo(DeadLetterReason.RETRY_EXHAUSTED);
        assertThat(sent.kind()).isEqualTo(OutcomeKind.SERVER_ERROR);
        assertThat(sent.statusCode()).isEqualTo(503);
        assertThat(sent.attempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("꺼낸 순간 만료되면 호출 횟수는 그전까지의 횟수로 보낸다")
    void reportsExpired() {
        OutboundRequest old = new OutboundRequest("req-old", TARGET, "{}", Instant.now().minus(Duration.ofMinutes(11)));
        DeliveryContext retry = new DeliveryContext(RetryStage.SECOND, 2, Instant.now(), Instant.now(), "o",
                OutcomeKind.TIMEOUT, "loan");

        service.handle(old, retry);

        RequestResult sent = capturedResult("loan");
        assertThat(sent.outcome()).isEqualTo(FinalOutcome.EXPIRED);
        assertThat(sent.expiredWhere()).isEqualTo(ExpiryPoint.INTAKE);
        assertThat(sent.kind()).isEqualTo(OutcomeKind.TIMEOUT);
        assertThat(sent.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("잘못된 메시지는 요청 ID 없이 원래 값을 담아 결과로 보낸다")
    void reportsInvalidMessage() {
        service.rejectInvalid(TARGET, "not-json", FROM_LOAN, "JSON 이 아닙니다");

        RequestResult sent = capturedResult("loan");
        assertThat(sent.requestId()).isNull();
        assertThat(sent.outcome()).isEqualTo(FinalOutcome.DEAD_LETTERED);
        assertThat(sent.deadLetterReason()).isEqualTo(DeadLetterReason.INVALID_MESSAGE);
        assertThat(sent.rawMessage()).isEqualTo("not-json");
        assertThat(sent.attempts()).isZero();
    }

    @Test
    @DisplayName("요청자를 모르면 결과를 보내지 않고 처리는 그대로 한다")
    void skipsResultWithoutRequester() {
        when(intake.process(eq(REQUEST), any())).thenReturn(new IntakeResult.Called(result(OutcomeKind.INVALID_REQUEST, null)));

        assertThat(service.handle(REQUEST, FIRST)).isEqualTo(new HandlingOutcome.DeadLettered(DeadLetterReason.DLQ_KIND));
        verify(publisher).deadLetter(eq(REQUEST), any(), eq(DeadLetterReason.DLQ_KIND), any(), isNull());
        verifyNoInteractions(results);
    }

    private RequestResult capturedResult(String requesterId) {
        ArgumentCaptor<RequestResult> result = ArgumentCaptor.forClass(RequestResult.class);
        verify(results).publish(eq(requesterId), result.capture());
        return result.getValue();
    }

    private DeliveryContext capturedRetryContext(OutcomeKind kind) {
        ArgumentCaptor<DeliveryContext> context = ArgumentCaptor.forClass(DeliveryContext.class);
        verify(publisher).scheduleRetry(eq(REQUEST), context.capture(), eq(kind));
        return context.getValue();
    }

    private static OutboundResult result(OutcomeKind kind, Instant retryAfter) {
        return new OutboundResult(kind, Duration.ofMillis(100), retryAfter, null, null, null);
    }
}
