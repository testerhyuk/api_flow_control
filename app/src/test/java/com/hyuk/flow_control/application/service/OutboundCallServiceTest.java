package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.TargetSystemClient;
import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.application.port.out.TargetSystemRateLimiter;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.domain.request.CallAttempt;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.token.RateLimitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OutboundCallServiceTest {
    private static final String TARGET = "target-a";
    private static final OutboundRequest REQUEST =
            new OutboundRequest("req-1", TARGET, "{}", Instant.now());
    private static final OutboundResult RESULT =
            new OutboundResult(OutcomeKind.SUCCESS, Duration.ofMillis(120), null, null);

    private TargetSystemSemaphore semaphore;
    private TargetSystemRateLimiter rateLimiter;
    private TargetSystemClient client;
    private OutboundCallService service;

    @BeforeEach
    void setUp() {
        TargetSystemPolicyReader policyReader = mock(TargetSystemPolicyReader.class);
        when(policyReader.readAll()).thenReturn(Map.of(
                TARGET, new TargetSystemPolicy(1, Duration.ofSeconds(1), 1.0, Duration.ofMillis(10))
        ));

        semaphore = new TargetSystemSemaphore(policyReader);
        rateLimiter = mock(TargetSystemRateLimiter.class);
        client = mock(TargetSystemClient.class);
        service = new OutboundCallService(client, semaphore, rateLimiter);
    }

    @Test
    @DisplayName("세마포어와 토큰을 모두 통과하면 외부를 호출하고 Called 를 돌려준다")
    void returnsCalledWhenBothPass() {
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenReturn(RESULT);

        CallAttempt attempt = service.send(REQUEST);

        assertThat(attempt).isEqualTo(new CallAttempt.Called(RESULT));
    }

    @Test
    @DisplayName("세마포어에서 막히면 BlockedBySemaphore 를 돌려주고 토큰과 외부 호출을 건드리지 않는다")
    void returnsBlockedBySemaphoreWithoutTouchingOthers() {
        semaphore.tryAcquire(TARGET);

        CallAttempt attempt = service.send(REQUEST);

        assertThat(attempt).isEqualTo(new CallAttempt.BlockedBySemaphore());
        verifyNoInteractions(rateLimiter, client);
    }

    @Test
    @DisplayName("토큰에서 막히면 BlockedByTokenBucket 을 돌려주고 외부를 호출하지 않는다")
    void returnsBlockedByTokenBucketWithoutCalling() {
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.reject(Duration.ofMillis(10)));

        CallAttempt attempt = service.send(REQUEST);

        assertThat(attempt).isEqualTo(new CallAttempt.BlockedByTokenBucket(Duration.ofMillis(10)));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("외부 호출이 끝나면 세마포어를 반납한다")
    void releasesAfterCall() {
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenReturn(RESULT);

        service.send(REQUEST);

        assertThat(semaphore.tryAcquire(TARGET)).isTrue();
        assertThat(semaphore.tryAcquire(TARGET)).isFalse();
    }

    @Test
    @DisplayName("토큰에서 막혀도 세마포어를 반납한다")
    void releasesWhenBlockedByTokenBucket() {
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.reject(Duration.ofMillis(10)));

        service.send(REQUEST);

        assertThat(semaphore.tryAcquire(TARGET)).isTrue();
        assertThat(semaphore.tryAcquire(TARGET)).isFalse();
    }

    @Test
    @DisplayName("외부 호출에서 예외가 나도 세마포어를 반납한다")
    void releasesWhenCallThrows() {
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenThrow(new IllegalStateException("예상하지 못한 예외"));

        assertThatThrownBy(() -> service.send(REQUEST))
                .isInstanceOf(IllegalStateException.class);

        assertThat(semaphore.tryAcquire(TARGET)).isTrue();
        assertThat(semaphore.tryAcquire(TARGET)).isFalse();
    }

    @Test
    @DisplayName("세마포어에서 막힌 경우에는 반납하지 않는다")
    void doesNotReleaseWhenBlockedBySemaphore() {
        semaphore.tryAcquire(TARGET);

        service.send(REQUEST);

        assertThat(semaphore.tryAcquire(TARGET)).isFalse();
    }

    @Test
    @DisplayName("외부가 실패로 답해도 호출은 한 것이므로 Called 를 돌려준다")
    void returnsCalledEvenWhenTargetFails() {
        OutboundResult failed =
                new OutboundResult(OutcomeKind.SERVER_ERROR, Duration.ofMillis(50), null, null);
        when(rateLimiter.tryConsume(TARGET)).thenReturn(RateLimitResult.allow());
        when(client.call(REQUEST)).thenReturn(failed);

        CallAttempt attempt = service.send(REQUEST);

        assertThat(attempt).isEqualTo(new CallAttempt.Called(failed));
    }

    @Test
    @DisplayName("토큰 버킷에서 예외가 나도 세마포어를 반납하고 외부를 호출하지 않는다")
    void releasesWhenRateLimiterThrows() {
        when(rateLimiter.tryConsume(TARGET)).thenThrow(new IllegalStateException("예상하지 못한 예외"));

        assertThatThrownBy(() -> service.send(REQUEST))
                .isInstanceOf(IllegalStateException.class);

        assertThat(semaphore.tryAcquire(TARGET)).isTrue();
        assertThat(semaphore.tryAcquire(TARGET)).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("설정에 없는 대상이면 예외가 나고 토큰과 외부 호출을 건드리지 않는다 (현재 동작 기록)")
    void throwsForUnknownTarget() {
        OutboundRequest unknown = new OutboundRequest("req-2", "target-z", "{}", Instant.now());

        assertThatThrownBy(() -> service.send(unknown))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(rateLimiter, client);
    }
}