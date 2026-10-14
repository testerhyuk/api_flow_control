package com.hyuk.mock_target.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DuplicateRegistryTest {
    private static final CallResult SUCCESS = CallResult.of(Outcome.SUCCESS);

    private final AtomicLong now = new AtomicLong(0);
    private final DuplicateRegistry registry = new DuplicateRegistry(Duration.ofMinutes(10), now::get);

    @Test
    @DisplayName("처음 받은 요청 ID 는 직접 처리한다")
    void firstClaimOwns() {
        assertThat(registry.claim("req-1").owner()).isTrue();
    }

    @Test
    @DisplayName("처리를 마친 성공 결과는 같은 ID 로 다시 오면 그대로 돌려준다")
    void returnsRememberedResult() throws Exception {
        DuplicateRegistry.Claim first = registry.claim("req-1");
        registry.complete(first, SUCCESS);

        DuplicateRegistry.Claim second = registry.claim("req-1");

        assertThat(second.owner()).isFalse();
        assertThat(second.awaitResult()).isEqualTo(SUCCESS);
    }

    @Test
    @DisplayName("업무 거절도 기억한다")
    void remembersBusinessRejection() {
        registry.complete(registry.claim("req-1"), CallResult.of(Outcome.BUSINESS_REJECTED));

        assertThat(registry.claim("req-1").owner()).isFalse();
    }

    @Test
    @DisplayName("처리를 마치지 못한 결과는 기억하지 않아서 다시 오면 새로 처리한다")
    void forgetsUnprocessedResults() {
        for (Outcome outcome : new Outcome[]{Outcome.SERVER_ERROR, Outcome.OVERLOADED}) {
            registry.complete(registry.claim("req-" + outcome), CallResult.of(outcome));

            assertThat(registry.claim("req-" + outcome).owner()).isTrue();
        }
    }

    @Test
    @DisplayName("기억 기간이 지나면 새 요청으로 처리한다")
    void forgetsAfterRetention() {
        registry.complete(registry.claim("req-1"), SUCCESS);

        now.set(Duration.ofMinutes(10).toMillis() - 1);
        assertThat(registry.claim("req-1").owner()).isFalse();

        now.set(Duration.ofMinutes(10).toMillis());
        assertThat(registry.claim("req-1").owner()).isTrue();
    }

    @Test
    @DisplayName("같은 ID 가 처리 중이면 그 결과가 나올 때까지 기다렸다가 같은 결과를 받는다")
    void waitsForInFlightRequest() throws Exception {
        DuplicateRegistry.Claim first = registry.claim("req-1");
        DuplicateRegistry.Claim second = registry.claim("req-1");
        CompletableFuture<CallResult> waiting = CompletableFuture.supplyAsync(() -> await(second));

        Thread.sleep(50);
        assertThat(waiting).isNotDone();

        CallResult serverError = CallResult.of(Outcome.SERVER_ERROR);
        registry.complete(first, serverError);

        assertThat(waiting.get(2, TimeUnit.SECONDS)).isEqualTo(serverError);
    }

    @Test
    @DisplayName("처음 요청이 중단되면 기다리던 요청도 예외로 끝나고 기록은 지운다")
    void abortedOwnerFailsWaiters() {
        DuplicateRegistry.Claim first = registry.claim("req-1");
        DuplicateRegistry.Claim second = registry.claim("req-1");

        registry.complete(first, null);

        assertThatThrownBy(second::awaitResult).isInstanceOf(IllegalStateException.class);
        assertThat(registry.claim("req-1").owner()).isTrue();
    }

    @Test
    @DisplayName("정리하면 기억 기간이 지난 기록만 지운다")
    void sweepRemovesExpiredOnly() {
        registry.complete(registry.claim("old"), SUCCESS);
        now.set(Duration.ofMinutes(5).toMillis());
        registry.complete(registry.claim("new"), SUCCESS);
        registry.claim("in-flight");

        now.set(Duration.ofMinutes(10).toMillis());

        assertThat(registry.sweep()).isEqualTo(1);
        assertThat(registry.size()).isEqualTo(2);
    }

    private static CallResult await(DuplicateRegistry.Claim claim) {
        try {
            return claim.awaitResult();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
