package com.hyuk.mock_target.fault;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultsTest {
    private final Faults faults = new Faults();

    @Test
    @DisplayName("아무 장애도 없으면 느려짐 배수는 1 이고 나머지는 비어 있다")
    void defaults() {
        assertThat(faults.slowMultiplier()).isEqualTo(1.0);
        assertThat(faults.failureRate()).isNull();
        assertThat(faults.maintenanceRetryAfterSeconds()).isNull();
        assertThat(faults.noResponseHold()).isNull();
        assertThat(faults.capacity()).isNull();
        assertThat(faults.describe()).isEmpty();
    }

    @Test
    @DisplayName("여러 장애를 동시에 걸고 하나씩 풀 수 있다")
    void appliesSeveralAtOnce() {
        faults.apply(FaultType.SLOW, 3.0);
        faults.apply(FaultType.FAILURE_RATE, 0.3);
        faults.apply(FaultType.NO_RESPONSE, 10.0);

        assertThat(faults.slowMultiplier()).isEqualTo(3.0);
        assertThat(faults.failureRate()).isEqualTo(0.3);
        assertThat(faults.noResponseHold()).isEqualTo(Duration.ofSeconds(10));
        assertThat(faults.describe()).isEqualTo(Map.of("slow", 3.0, "failure-rate", 0.3, "no-response", 10.0));

        faults.clear(FaultType.SLOW);

        assertThat(faults.isActive(FaultType.SLOW)).isFalse();
        assertThat(faults.isActive(FaultType.FAILURE_RATE)).isTrue();
    }

    @Test
    @DisplayName("다운, 점검, 처리 능력 감소를 건다")
    void appliesDownMaintenanceCapacity() {
        faults.apply(FaultType.DOWN, null);
        faults.apply(FaultType.MAINTENANCE, 30.0);
        faults.apply(FaultType.CAPACITY, 5.0);

        assertThat(faults.isActive(FaultType.DOWN)).isTrue();
        assertThat(faults.maintenanceRetryAfterSeconds()).isEqualTo(30L);
        assertThat(faults.capacity()).isEqualTo(5);
    }

    @Test
    @DisplayName("장애에 필요한 값이 없거나 범위를 벗어나면 걸 수 없다")
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> faults.apply(FaultType.SLOW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> faults.apply(FaultType.SLOW, 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> faults.apply(FaultType.FAILURE_RATE, 1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> faults.apply(FaultType.MAINTENANCE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> faults.apply(FaultType.MAINTENANCE, 1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> faults.apply(FaultType.NO_RESPONSE, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> faults.apply(FaultType.CAPACITY, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(faults.describe()).isEmpty();
    }

    @Test
    @DisplayName("장애 이름으로 종류를 찾고, 모르는 이름이면 예외가 난다")
    void findsTypeById() {
        assertThat(FaultType.fromId("no-response")).isEqualTo(FaultType.NO_RESPONSE);
        assertThatThrownBy(() -> FaultType.fromId("boom")).isInstanceOf(IllegalArgumentException.class);
    }
}
