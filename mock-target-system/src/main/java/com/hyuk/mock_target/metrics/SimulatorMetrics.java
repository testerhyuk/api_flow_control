package com.hyuk.mock_target.metrics;

import com.hyuk.mock_target.fault.FaultType;
import com.hyuk.mock_target.fault.Faults;
import com.hyuk.mock_target.simulation.Outcome;
import com.hyuk.mock_target.simulation.WorkSlots;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

public class SimulatorMetrics {
    private static final Duration MIN_EXPECTED_LATENCY = Duration.ofMillis(1);
    private static final Duration MAX_EXPECTED_LATENCY = Duration.ofSeconds(60);

    private final MeterRegistry registry;
    private final Counter duplicates;

    public SimulatorMetrics(MeterRegistry registry, WorkSlots slots, Faults faults) {
        this.registry = registry;
        this.duplicates = Counter.builder("mock.target.duplicates")
                .description("같은 요청 ID 로 다시 들어와 다시 처리하지 않은 요청 수")
                .register(registry);

        Gauge.builder("mock.target.inflight", slots, WorkSlots::inUse)
                .description("지금 처리 중인 요청 수")
                .register(registry);
        Gauge.builder("mock.target.queue", slots, WorkSlots::waiting)
                .description("대기열에서 기다리는 요청 수")
                .register(registry);
        Gauge.builder("mock.target.capacity", slots, WorkSlots::capacity)
                .description("동시처리 자리 수")
                .register(registry);

        for (FaultType type : FaultType.values()) {
            Gauge.builder("mock.target.fault", faults, f -> f.isActive(type) ? 1 : 0)
                    .description("걸려 있는 장애 (1 이면 걸려 있음)")
                    .tag("type", type.id())
                    .register(registry);
        }
    }

    public void recordCall(Outcome outcome, Duration latency) {
        Counter.builder("mock.target.requests")
                .tag("outcome", outcome.name())
                .register(registry)
                .increment();

        Timer.builder("mock.target.latency")
                .tag("outcome", outcome.name())
                .publishPercentileHistogram()
                .minimumExpectedValue(MIN_EXPECTED_LATENCY)
                .maximumExpectedValue(MAX_EXPECTED_LATENCY)
                .register(registry)
                .record(latency);
    }

    public void recordDuplicate() {
        duplicates.increment();
    }
}
