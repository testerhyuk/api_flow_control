package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.TargetSystemPolicyReader;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TargetSystemSemaphoreTest {
    private TargetSystemSemaphore semaphore;

    @BeforeEach
    void setUp() {
        TargetSystemPolicyReader policyReader = mock(TargetSystemPolicyReader.class);
        when(policyReader.readAll()).thenReturn(Map.of(
                "target-a", policyWithConcurrencyLimit(2),
                "target-b", policyWithConcurrencyLimit(1)
        ));

        semaphore = new TargetSystemSemaphore(policyReader);
    }

    @Test
    @DisplayName("허가 개수만큼만 잡을 수 있다")
    void acquiresUpToLimit() {
        assertThat(semaphore.tryAcquire("target-a")).isTrue();
        assertThat(semaphore.tryAcquire("target-a")).isTrue();
        assertThat(semaphore.tryAcquire("target-a")).isFalse();
    }

    @Test
    @DisplayName("반납하면 다시 잡을 수 있다")
    void acquiresAgainAfterRelease() {
        semaphore.tryAcquire("target-a");
        semaphore.tryAcquire("target-a");

        semaphore.release("target-a");

        assertThat(semaphore.tryAcquire("target-a")).isTrue();
        assertThat(semaphore.tryAcquire("target-a")).isFalse();
    }

    @Test
    @DisplayName("한 대상이 가득 차도 다른 대상은 잡을 수 있다")
    void targetsAreIndependent() {
        semaphore.tryAcquire("target-a");
        semaphore.tryAcquire("target-a");

        assertThat(semaphore.tryAcquire("target-b")).isTrue();
    }

    @Test
    @DisplayName("설정에 없는 대상이면 예외가 난다")
    void rejectsUnknownTarget() {
        assertThatThrownBy(() -> semaphore.tryAcquire("target-z"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> semaphore.release("target-z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잡지 않고 반납하면 허가가 원래 개수보다 늘어난다 (현재 동작 기록)")
    void releaseWithoutAcquireAddsPermit() {
        semaphore.release("target-b");

        assertThat(semaphore.tryAcquire("target-b")).isTrue();
        assertThat(semaphore.tryAcquire("target-b")).isTrue();
        assertThat(semaphore.tryAcquire("target-b")).isFalse();
    }

    @Test
    @DisplayName("진행 중인 요청 수는 잡으면 늘고 반납하면 줄어든다")
    void tracksInFlight() {
        assertThat(semaphore.inFlightOf("target-a")).isZero();

        semaphore.tryAcquire("target-a");
        semaphore.tryAcquire("target-a");
        semaphore.tryAcquire("target-a");
        assertThat(semaphore.inFlightOf("target-a")).isEqualTo(2);

        semaphore.release("target-a");
        assertThat(semaphore.inFlightOf("target-a")).isEqualTo(1);
        assertThatThrownBy(() -> semaphore.inFlightOf("target-z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("처음 한도는 설정으로 계산한 허가 개수다")
    void initialLimit() {
        assertThat(semaphore.limitOf("target-a")).isEqualTo(2);
    }

    @Test
    @DisplayName("한도를 늘리면 늘어난 만큼 더 잡을 수 있다")
    void acquiresMoreAfterIncrease() {
        semaphore.changeLimit("target-a", 3);

        assertThat(semaphore.limitOf("target-a")).isEqualTo(3);
        assertThat(semaphore.tryAcquire("target-a")).isTrue();
        assertThat(semaphore.tryAcquire("target-a")).isTrue();
        assertThat(semaphore.tryAcquire("target-a")).isTrue();
        assertThat(semaphore.tryAcquire("target-a")).isFalse();
    }

    @Test
    @DisplayName("한도를 줄여도 진행 중인 요청은 그대로 두고, 새 한도보다 줄어들 때까지 새 요청을 막는다")
    void blocksUntilInFlightDropsBelowReducedLimit() {
        semaphore.tryAcquire("target-a");
        semaphore.tryAcquire("target-a");

        semaphore.changeLimit("target-a", 1);
        assertThat(semaphore.tryAcquire("target-a")).isFalse();

        semaphore.release("target-a");
        assertThat(semaphore.tryAcquire("target-a")).isFalse();

        semaphore.release("target-a");
        assertThat(semaphore.tryAcquire("target-a")).isTrue();
        assertThat(semaphore.tryAcquire("target-a")).isFalse();
    }

    @Test
    @DisplayName("한 대상의 한도를 바꿔도 다른 대상은 그대로다")
    void changeLimitIsPerTarget() {
        semaphore.changeLimit("target-a", 5);

        assertThat(semaphore.limitOf("target-b")).isEqualTo(1);
    }

    @Test
    @DisplayName("한도를 1 보다 작게 바꿀 수 없다")
    void rejectsLimitBelowOne() {
        assertThatThrownBy(() -> semaphore.changeLimit("target-a", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(semaphore.limitOf("target-a")).isEqualTo(2);
    }

    @Test
    @DisplayName("설정에 없는 대상의 한도는 바꾸거나 조회할 수 없다")
    void rejectsUnknownTargetForLimit() {
        assertThatThrownBy(() -> semaphore.changeLimit("target-z", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> semaphore.limitOf("target-z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TargetSystemPolicy policyWithConcurrencyLimit(int limit) {
        return new TargetSystemPolicy(limit, Duration.ofSeconds(1), 1.0, Duration.ofMillis(10));
    }
}