package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.OutboundCallUseCase;
import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.domain.request.CallAttempt;
import com.hyuk.flow_control.domain.request.IntakeResult;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequestIntakeServiceTest {
    private static final String TARGET = "target-a";
    private static final OutboundRequest REQUEST =
            new OutboundRequest("req-1", TARGET, "{}", Instant.now());
    private static final OutboundResult RESULT =
            new OutboundResult(OutcomeKind.SUCCESS, Duration.ofMillis(120), null, null, null, null);
    private static final IntakeResult CALLED = new IntakeResult.Called(RESULT);

    private OutboundCallUseCase outboundCall;
    private TargetSystemSemaphore semaphore;
    private TargetSystemOutcomeRecorder recorder;
    private FlowControlMetrics metrics;
    private DryRunMonitor dryRuns;
    private RequestIntakeService service;

    @BeforeEach
    void setUp() throws InterruptedException {
        outboundCall = mock(OutboundCallUseCase.class);
        semaphore = mock(TargetSystemSemaphore.class);
        when(semaphore.awaitAvailable(eq(TARGET), any())).thenReturn(true);
        recorder = mock(TargetSystemOutcomeRecorder.class);
        metrics = mock(FlowControlMetrics.class);
        dryRuns = mock(DryRunMonitor.class);
        service = new RequestIntakeService(outboundCall, semaphore, recorder, metrics, dryRuns);
    }

    private static Instant later() {
        return Instant.now().plusSeconds(60);
    }

    @Test
    @DisplayName("바로 호출되면 결과를 돌려준다")
    void returnsResultWhenCalled() {
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.Called(RESULT));

        assertThat(service.process(REQUEST, later())).isEqualTo(CALLED);
        verify(outboundCall, times(1)).send(REQUEST);
    }

    @Test
    @DisplayName("외부 기관이 실패로 답해도 호출은 한 것이므로 다시 보내지 않는다")
    void doesNotRetryFailedResult() {
        OutboundResult failed = new OutboundResult(OutcomeKind.TIMEOUT, Duration.ofSeconds(3), null, null, null, null);
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.Called(failed));

        assertThat(service.process(REQUEST, later())).isEqualTo(new IntakeResult.Called(failed));
        verify(outboundCall, times(1)).send(REQUEST);
    }

    @Test
    @DisplayName("토큰 버킷에서 막히면 알려 준 시간만큼 기다렸다가 같은 요청을 다시 보낸다")
    void retriesAfterTokenBucketWait() {
        when(outboundCall.send(REQUEST)).thenReturn(
                new CallAttempt.BlockedByTokenBucket(Duration.ofMillis(100)),
                new CallAttempt.Called(RESULT));

        long started = System.nanoTime();
        IntakeResult result = service.process(REQUEST, later());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result).isEqualTo(CALLED);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(100));
        verify(outboundCall, times(2)).send(REQUEST);
    }

    @Test
    @DisplayName("세마포어에서 막히면 자리가 날 때까지 기다렸다가 다시 보낸다")
    void retriesAfterSemaphoreWait() throws InterruptedException {
        Instant deadline = later();
        when(outboundCall.send(REQUEST)).thenReturn(
                new CallAttempt.BlockedBySemaphore(),
                new CallAttempt.Called(RESULT));

        assertThat(service.process(REQUEST, deadline)).isEqualTo(CALLED);
        verify(semaphore).awaitAvailable(TARGET, deadline);
        verify(outboundCall, times(2)).send(REQUEST);
    }

    @Test
    @DisplayName("세마포어 자리를 기다리는 동안 기다리는 중이라고 기록한다")
    void marksWaitingAroundSemaphoreWait() throws InterruptedException {
        when(outboundCall.send(REQUEST)).thenReturn(
                new CallAttempt.BlockedBySemaphore(),
                new CallAttempt.Called(RESULT));

        service.process(REQUEST, later());

        InOrder order = inOrder(recorder, semaphore);
        order.verify(recorder).waitStarted(TARGET);
        order.verify(semaphore).awaitAvailable(eq(TARGET), any());
        order.verify(recorder).waitEnded(TARGET);
    }

    @Test
    @DisplayName("세마포어 자리를 기다리다 유효기간이 지나면 다시 보내지 않고 만료를 돌려준다")
    void expiresWhenSemaphoreWaitPassesDeadline() throws InterruptedException {
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.BlockedBySemaphore());
        when(semaphore.awaitAvailable(eq(TARGET), any())).thenReturn(false);

        assertThat(service.process(REQUEST, later())).isInstanceOf(IntakeResult.Expired.class);
        verify(outboundCall, times(1)).send(REQUEST);
        verify(recorder).waitEnded(TARGET);
        verify(metrics, never()).recordIntakeWait(any(), any());
    }

    @Test
    @DisplayName("유효기간이 이미 지났으면 보내지 않고 만료를 돌려준다")
    void expiresBeforeSendingWhenDeadlinePassed() {
        assertThat(service.process(REQUEST, Instant.now().minusMillis(1))).isInstanceOf(IntakeResult.Expired.class);
        verifyNoInteractions(outboundCall);
    }

    @Test
    @DisplayName("토큰을 기다리는 시간은 유효기간이 끝나는 시각까지로 줄이고, 지나면 만료를 돌려준다")
    void capsTokenWaitAtDeadline() {
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.BlockedByTokenBucket(Duration.ofSeconds(10)));

        long started = System.nanoTime();
        IntakeResult result = service.process(REQUEST, Instant.now().plusMillis(100));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result).isInstanceOf(IntakeResult.Expired.class);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        verify(outboundCall, times(1)).send(REQUEST);
    }

    @Test
    @DisplayName("마감 시각이 없으면 유효기간을 확인하지 않고 기다린다")
    void waitsWithoutDeadline() throws InterruptedException {
        when(outboundCall.send(REQUEST)).thenReturn(
                new CallAttempt.BlockedBySemaphore(),
                new CallAttempt.Called(RESULT));

        assertThat(service.process(REQUEST, null)).isEqualTo(CALLED);
        verify(semaphore).awaitAvailable(TARGET, null);
    }

    @Test
    @DisplayName("대기 시간이 0 이어도 최소 대기 시간만큼은 기다린다")
    void waitsAtLeastMinimum() {
        when(outboundCall.send(REQUEST)).thenReturn(
                new CallAttempt.BlockedByTokenBucket(Duration.ZERO),
                new CallAttempt.Called(RESULT));

        long started = System.nanoTime();
        service.process(REQUEST, later());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isGreaterThanOrEqualTo(RequestIntakeService.MINIMUM_WAIT);
    }

    @Test
    @DisplayName("토큰을 기다리는 중에 스레드가 중단되면 예외를 내고 중단 표시를 남긴다")
    void stopsWhenInterrupted() throws InterruptedException {
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.BlockedByTokenBucket(Duration.ofSeconds(10)));

        assertInterruptedStops();
    }

    @Test
    @DisplayName("세마포어 자리를 기다리는 중에 스레드가 중단되면 예외를 내고 기다림 기록을 끝낸다")
    void stopsWhenInterruptedWhileWaitingForSemaphore() throws InterruptedException {
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.BlockedBySemaphore());
        when(semaphore.awaitAvailable(eq(TARGET), any())).thenThrow(new InterruptedException());

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        try {
            service.process(REQUEST, later());
        } catch (Throwable e) {
            thrown.set(e);
        }

        assertThat(thrown.get()).isInstanceOf(IntakeInterruptedException.class);
        assertThat(Thread.interrupted()).isTrue();
        verify(recorder).waitEnded(TARGET);
    }

    private void assertInterruptedStops() throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interruptedFlag = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                service.process(REQUEST, later());
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

        service.process(REQUEST, later());

        verify(metrics).recordIntakeWait(TARGET, Duration.ZERO);
    }

    @Test
    @DisplayName("한도 때문에 기다렸으면 기다린 총시간을 기록한다")
    void recordsTotalWait() {
        when(outboundCall.send(REQUEST)).thenReturn(
                new CallAttempt.BlockedByTokenBucket(Duration.ofMillis(50)),
                new CallAttempt.BlockedBySemaphore(),
                new CallAttempt.Called(RESULT));

        service.process(REQUEST, later());

        verify(metrics).recordIntakeWait(eq(TARGET),
                argThat(waited -> waited.compareTo(Duration.ofMillis(50)) >= 0 && waited.compareTo(Duration.ofSeconds(1)) < 0));
    }

    @Test
    @DisplayName("대기 중에 중단되면 대기 시간을 기록하지 않는다")
    void doesNotRecordWaitWhenInterrupted() throws InterruptedException {
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.BlockedByTokenBucket(Duration.ofSeconds(10)));
        Thread worker = new Thread(() -> {
            try {
                service.process(REQUEST, later());
            } catch (IntakeInterruptedException ignored) {
            }
        });
        worker.start();
        Thread.sleep(100);
        worker.interrupt();
        worker.join(2000);

        verify(metrics, never()).recordIntakeWait(any(), any());
    }

    @Test
    @DisplayName("한도 때문에 기다렸다가 호출되면 dry-run 에 기다린 호출로 알린다")
    void notifiesDryRunWhenWaited() {
        when(outboundCall.send(REQUEST)).thenReturn(
                new CallAttempt.BlockedBySemaphore(),
                new CallAttempt.Called(RESULT));

        service.process(REQUEST, later());

        verify(dryRuns).recordWaited(TARGET);
    }

    @Test
    @DisplayName("기다리지 않고 바로 호출되면 dry-run 에 알리지 않는다")
    void doesNotNotifyDryRunWithoutWaiting() {
        when(outboundCall.send(REQUEST)).thenReturn(new CallAttempt.Called(RESULT));

        service.process(REQUEST, later());

        verifyNoInteractions(dryRuns);
    }
}
