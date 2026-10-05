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

    private static TargetSystemPolicy policyWithConcurrencyLimit(int limit) {
        return new TargetSystemPolicy(limit, Duration.ofSeconds(1), 1.0, Duration.ofMillis(10));
    }
}