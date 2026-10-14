package com.hyuk.mock_target.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class WorkSlotsTest {

    @Test
    @DisplayName("자리가 있으면 바로 들어간다")
    void entersWhenFree() throws Exception {
        WorkSlots slots = new WorkSlots(2, 0);

        assertThat(slots.enter()).isTrue();
        assertThat(slots.enter()).isTrue();
        assertThat(slots.inUse()).isEqualTo(2);
    }

    @Test
    @DisplayName("자리와 대기열이 모두 차면 들어가지 못한다")
    void rejectsWhenQueueIsFull() throws Exception {
        WorkSlots slots = new WorkSlots(1, 0);
        slots.enter();

        assertThat(slots.enter()).isFalse();
        assertThat(slots.waiting()).isZero();
    }

    @Test
    @DisplayName("자리가 없으면 대기열에서 기다리다가 자리가 나면 들어간다")
    void waitsInQueue() throws Exception {
        WorkSlots slots = new WorkSlots(1, 1);
        slots.enter();

        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> enter(slots));
        await().atMost(2, TimeUnit.SECONDS).until(() -> slots.waiting() == 1);
        assertThat(slots.enter()).isFalse();

        slots.leave();

        assertThat(waiter.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(slots.inUse()).isEqualTo(1);
        assertThat(slots.waiting()).isZero();
    }

    @Test
    @DisplayName("자리 수를 늘리면 기다리던 요청이 들어간다")
    void increasingCapacityLetsWaiterIn() throws Exception {
        WorkSlots slots = new WorkSlots(1, 1);
        slots.enter();
        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> enter(slots));
        await().atMost(2, TimeUnit.SECONDS).until(() -> slots.waiting() == 1);

        slots.changeCapacity(2);

        assertThat(waiter.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(slots.capacity()).isEqualTo(2);
    }

    @Test
    @DisplayName("자리 수를 줄이면 처리 중인 요청이 빠질 때까지 새 요청은 기다린다")
    void decreasingCapacityHoldsNewRequests() throws Exception {
        WorkSlots slots = new WorkSlots(2, 0);
        slots.enter();
        slots.enter();

        slots.changeCapacity(1);
        slots.leave();

        assertThat(slots.enter()).isFalse();

        slots.leave();
        assertThat(slots.enter()).isTrue();
    }

    private static boolean enter(WorkSlots slots) {
        try {
            return slots.enter();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
