package com.hyuk.mock_target.fault;

import com.hyuk.mock_target.simulation.WorkSlots;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class FaultServiceTest {
    private final Faults faults = new Faults();
    private final WorkSlots slots = new WorkSlots(10, 0);
    private final CallPort callPort = mock(CallPort.class);
    private final FaultService service = new FaultService(faults, slots, callPort, 10);

    @Test
    @DisplayName("다운을 걸면 /call 포트를 닫고, 풀면 다시 연다")
    void downClosesAndOpensPort() {
        service.apply(FaultType.DOWN, null);
        service.apply(FaultType.DOWN, null);
        verify(callPort, times(1)).close();

        service.clear(FaultType.DOWN);
        service.clear(FaultType.DOWN);
        verify(callPort, times(1)).open();
    }

    @Test
    @DisplayName("처리 능력 감소를 걸면 동시처리 자리를 줄이고, 풀면 설정값으로 되돌린다")
    void capacityChangesSlots() {
        service.apply(FaultType.CAPACITY, 3.0);
        assertThat(slots.capacity()).isEqualTo(3);

        service.clear(FaultType.CAPACITY);
        assertThat(slots.capacity()).isEqualTo(10);
    }

    @Test
    @DisplayName("다운과 처리 능력 감소가 아닌 장애는 포트와 자리를 건드리지 않는다")
    void otherFaultsTouchNothingElse() {
        service.apply(FaultType.SLOW, 2.0);
        service.clear(FaultType.SLOW);

        verifyNoInteractions(callPort);
        assertThat(slots.capacity()).isEqualTo(10);
    }

    @Test
    @DisplayName("모두 풀면 걸려 있던 장애를 전부 되돌린다")
    void clearAllRestoresEverything() {
        service.apply(FaultType.DOWN, null);
        service.apply(FaultType.CAPACITY, 2.0);
        service.apply(FaultType.FAILURE_RATE, 0.5);

        assertThat(service.clearAll()).isEmpty();
        verify(callPort).open();
        assertThat(slots.capacity()).isEqualTo(10);
    }
}
