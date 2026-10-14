package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.OutboundCallUseCase;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.domain.request.CallAttempt;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;

class RequestIntakeServiceTest {
    private static final OutboundRequest REQUEST =
            new OutboundRequest("req-1", "target-a", "{}", Instant.now());
    private static final OutboundResult RESULT =
            new OutboundResult(OutcomeKind.SUCCESS, Duration.ofMillis(120), null, null, null, null);

    private OutboundCallUseCase outboundCall;
    private FlowControlMetrics metrics;
    private RequestIntakeService service;

    @BeforeEach
    void setUp() {
        outboundCall = mock(OutboundCallUseCase.class);
        metrics = mock(FlowControlMetrics.class);
        service = new RequestIntakeService(outboundCall, metrics);
    }

    @Test
    @DisplayName("바로 호출되면 결과를 돌려준다")
    void returnsResultWhenCalled() {
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.Called(RESULT));

        assertThat(service.process(REQUEST)).isEqualTo(RESULT);
        verify(outboundCall, times(1)).send(REQUEST);
    }

    @Test
    @DisplayName("외부 기관이 실패로 답해도 호출은 한 것이므로 다시 보내지 않는다")
    void doesNotRetryFailedResult() {
        OutboundResult failed = new OutboundResult(OutcomeKind.TIMEOUT, Duration.ofSeconds(3), null, null, null, null);
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.Called(failed));

        assertThat(service.process(REQUEST)).isEqualTo(failed);
        verify(outboundCall, times(1)).send(REQUEST);
    }

    @Test
    @DisplayName("토큰 버킷에서 막히면 알려 준 시간만큼 기다렸다가 같은 요청을 다시 보낸다")
    void retriesAfterTokenBucketWait() {
        when(outboundCall.send(REQUEST)).thenReturn(
                new CallAttempt.BlockedByTokenBucket(Duration.ofMillis(100)),
                new CallAttempt.Called(RESULT));

        long started = System.nanoTime();
        OutboundResult result = service.process(REQUEST);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result).isEqualTo(RESULT);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(100));
        verify(outboundCall, times(2)).send(REQUEST);
    }

    @Test
    @DisplayName("세마포어에서 막히면 짧게 기다렸다가 다시 보낸다")
    void retriesAfterSemaphoreWait() {
        when(outboundCall.send(REQUEST)).thenReturn(
                new CallAttempt.BlockedBySemaphore(),
                new CallAttempt.BlockedBySemaphore(),
                new CallAttempt.Called(RESULT));

        long started = System.nanoTime();
        OutboundResult result = service.process(REQUEST);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result).isEqualTo(RESULT);
        assertThat(elapsed).isGreaterThanOrEqualTo(RequestIntakeService.SEMAPHORE_RETRY_INTERVAL.multipliedBy(2));
        verify(outboundCall, times(3)).send(REQUEST);
    }

    @Test
    @DisplayName("대기 시간이 0 이어도 최소 대기 시간만큼은 기다린다")
    void waitsAtLeastMinimum() {
        when(outboundCall.send(REQUEST)).thenReturn(
                new CallAttempt.BlockedByTokenBucket(Duration.ZERO),
                new CallAttempt.Called(RESULT));

        long started = System.nanoTime();
        service.process(REQUEST);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isGreaterThanOrEqualTo(RequestIntakeService.MINIMUM_WAIT);
    }

    @Test
    @DisplayName("기다리는 중에 스레드가 중단되면 예외를 내고 중단 표시를 남긴다")
    void stopsWhenInterrupted() throws InterruptedException {
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.BlockedByTokenBucket(Duration.ofSeconds(10)));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interruptedFlag = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                service.process(REQUEST);
            } catch (Throwable e) {
                thrown.set(e);
                interruptedFlag.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        Thread.sleep(100);
        worker.interrupt();
        worker.join(2000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(IntakeInterruptedException.class);
        assertThat(interruptedFlag.get()).isTrue();
    }

    @Test
    @DisplayName("바로 호출되면 한도 대기 시간을 0 으로 기록한다")
    void recordsZeroWait() {
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.Called(RESULT));

        service.process(REQUEST);

        verify(metrics).recordIntakeWait("target-a", Duration.ZERO);
    }

    @Test
    @DisplayName("한도 때문에 기다렸으면 기다린 총시간을 기록한다")
    void recordsTotalWait() {
        when(outboundCall.send(REQUEST)).thenReturn(
                new CallAttempt.BlockedByTokenBucket(Duration.ofMillis(50)),
                new CallAttempt.BlockedBySemaphore(),
                new CallAttempt.Called(RESULT));

        service.process(REQUEST);

        verify(metrics).recordIntakeWait(eq("target-a"),
                argThat(waited -> waited.compareTo(Duration.ofMillis(60)) >= 0 && waited.compareTo(Duration.ofSeconds(1)) < 0));
    }

    @Test
    @DisplayName("대기 중에 중단되면 대기 시간을 기록하지 않는다")
    void doesNotRecordWaitWhenInterrupted() throws InterruptedException {
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.BlockedByTokenBucket(Duration.ofSeconds(10)));
        Thread worker = new Thread(() -> {
            try {
                service.process(REQUEST);
            } catch (IntakeInterruptedException ignored) {
            }
        });
        worker.start();
        Thread.sleep(100);
        worker.interrupt();
        worker.join(2000);

        verify(metrics, never()).recordIntakeWait(any(), any());
    }
}