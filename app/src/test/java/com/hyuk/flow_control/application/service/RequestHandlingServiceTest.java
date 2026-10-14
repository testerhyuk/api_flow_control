package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.RequestIntakeUseCase;
import com.hyuk.flow_control.application.port.out.FailedRequestPublisher;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
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
import static org.mockito.Mockito.when;

class RequestHandlingServiceTest {
    private static final String TARGET = "target-a";
    private static final OutboundRequest REQUEST = new OutboundRequest("req-1", TARGET, "{}", Instant.now());
    private static final DeliveryContext FIRST = DeliveryContext.first("flow-control.requests.target-a-0@7");

    private RequestIntakeUseCase intake;
    private FailedRequestPublisher publisher;
    private FlowControlMetrics metrics;
    private RequestHandlingService service;

    @BeforeEach
    void setUp() {
        intake = mock(RequestIntakeUseCase.class);
        publisher = mock(FailedRequestPublisher.class);
        metrics = mock(FlowControlMetrics.class);
        service = new RequestHandlingService(intake, publisher, metrics);
    }

    @Test
    @DisplayName("성공하면 끝내고 아무 데도 보내지 않는다")
    void completesOnSuccess() {
        when(intake.process(REQUEST)).thenReturn(result(OutcomeKind.SUCCESS, null));

        assertThat(service.handle(REQUEST, FIRST)).isEqualTo(new HandlingOutcome.Completed(OutcomeKind.SUCCESS));
        verifyNoInteractions(publisher, metrics);
    }

    @Test
    @DisplayName("재시도에서 성공하면 그 단계의 재시도 성공을 지표로 남긴다")
    void recordsRetrySuccess() {
        when(intake.process(REQUEST)).thenReturn(result(OutcomeKind.SUCCESS, null));
        DeliveryContext retry = FIRST.afterFailure(Instant.now()).movedTo(RetryStage.SECOND);

        service.handle(REQUEST, retry);

        verify(metrics).recordRetrySucceeded(TARGET, RetryStage.SECOND);
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("BUSINESS_REJECTED 는 폐기하고 아무 데도 보내지 않는다")
    void discardsBusinessRejected() {
        when(intake.process(REQUEST)).thenReturn(result(OutcomeKind.BUSINESS_REJECTED, null));

        assertThat(service.handle(REQUEST, FIRST)).isEqualTo(new HandlingOutcome.Discarded(OutcomeKind.BUSINESS_REJECTED));
        verifyNoInteractions(publisher, metrics);
    }

    @ParameterizedTest
    @EnumSource(value = OutcomeKind.class, names = {
            "CONNECTION_FAILED", "SERVER_ERROR", "TOO_MANY_REQUESTS", "UNDER_MAINTENANCE", "TIMEOUT", "NETWORK_ERROR"})
    @DisplayName("처음 실패한 재시도 대상은 1초 단계로 보내고 실패 횟수를 1로 남긴다")
    void schedulesFirstRetry(OutcomeKind kind) {
        when(intake.process(REQUEST)).thenReturn(result(kind, null));

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
        when(intake.process(REQUEST)).thenReturn(result(OutcomeKind.TIMEOUT, null));

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
        when(intake.process(REQUEST)).thenReturn(failed);

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
        when(intake.process(REQUEST)).thenReturn(
                result(OutcomeKind.TOO_MANY_REQUESTS, Instant.now().plusSeconds(30)));

        assertThat(service.handle(REQUEST, FIRST))
                .isEqualTo(new HandlingOutcome.RetryScheduled(RetryStage.THIRD, OutcomeKind.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("알려 준 대기 시간이 이미 지났으면 1초 단계로 보낸다")
    void pastRetryAfterUsesFirstStage() {
        when(intake.process(REQUEST)).thenReturn(
                result(OutcomeKind.TOO_MANY_REQUESTS, Instant.now().minusSeconds(5)));

        assertThat(service.handle(REQUEST, FIRST))
                .isEqualTo(new HandlingOutcome.RetryScheduled(RetryStage.FIRST, OutcomeKind.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("알려 준 대기 시간이 마지막 단계보다 길면 재시도 소진으로 DLQ 에 보낸다")
    void retryAfterLongerThanLastStage() {
        when(intake.process(REQUEST)).thenReturn(
                result(OutcomeKind.UNDER_MAINTENANCE, Instant.now().plusSeconds(3600)));

        assertThat(service.handle(REQUEST, FIRST))
                .isEqualTo(new HandlingOutcome.DeadLettered(DeadLetterReason.RETRY_EXHAUSTED));
    }

    @ParameterizedTest
    @EnumSource(value = OutcomeKind.class, names = {"INVALID_REQUEST", "UNEXPECTED_RESPONSE"})
    @DisplayName("DLQ 대상 kind 는 재시도 없이 바로 DLQ 에 보낸다")
    void deadLettersPermanentFailures(OutcomeKind kind) {
        OutboundResult failed = new OutboundResult(kind, Duration.ofMillis(100), null, "{}", 200, "E999");
        when(intake.process(REQUEST)).thenReturn(failed);

        assertThat(service.handle(REQUEST, FIRST)).isEqualTo(new HandlingOutcome.DeadLettered(DeadLetterReason.DLQ_KIND));
        ArgumentCaptor<DeliveryContext> context = ArgumentCaptor.forClass(DeliveryContext.class);
        verify(publisher).deadLetter(eq(REQUEST), context.capture(), eq(DeadLetterReason.DLQ_KIND), eq(failed), isNull());
        assertThat(context.getValue().failedAttempts()).isEqualTo(1);
        verify(metrics).recordDeadLetter(TARGET, DeadLetterReason.DLQ_KIND);
    }

    @Test
    @DisplayName("처리 중 예외가 나면 예외 내용을 담아 DLQ 에 보낸다")
    void deadLettersProcessingError() {
        when(intake.process(REQUEST)).thenThrow(new IllegalArgumentException("설정에 없는 대상"));

        assertThat(service.handle(REQUEST, FIRST))
                .isEqualTo(new HandlingOutcome.DeadLettered(DeadLetterReason.PROCESSING_ERROR));
        verify(publisher).deadLetter(eq(REQUEST), any(), eq(DeadLetterReason.PROCESSING_ERROR), isNull(),
                eq("IllegalArgumentException: 설정에 없는 대상"));
        verify(metrics).recordDeadLetter(TARGET, DeadLetterReason.PROCESSING_ERROR);
    }

    @Test
    @DisplayName("앱 종료로 대기가 중단되면 DLQ 에 보내지 않고 그대로 예외를 낸다")
    void rethrowsInterruption() {
        when(intake.process(REQUEST)).thenThrow(new IntakeInterruptedException(new InterruptedException()));

        assertThatThrownBy(() -> service.handle(REQUEST, FIRST)).isInstanceOf(IntakeInterruptedException.class);
        verifyNoInteractions(publisher, metrics);
    }

    @Test
    @DisplayName("재시도 토픽에 넣다가 실패하면 예외를 내서 처리 완료로 기록되지 않게 한다")
    void propagatesPublishFailure() {
        when(intake.process(REQUEST)).thenReturn(result(OutcomeKind.TIMEOUT, null));
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

    private DeliveryContext capturedRetryContext(OutcomeKind kind) {
        ArgumentCaptor<DeliveryContext> context = ArgumentCaptor.forClass(DeliveryContext.class);
        verify(publisher).scheduleRetry(eq(REQUEST), context.capture(), eq(kind));
        return context.getValue();
    }

    private static OutboundResult result(OutcomeKind kind, Instant retryAfter) {
        return new OutboundResult(kind, Duration.ofMillis(100), retryAfter, null, null, null);
    }
}
