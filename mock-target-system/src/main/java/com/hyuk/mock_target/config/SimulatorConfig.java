package com.hyuk.mock_target.config;

import com.hyuk.mock_target.fault.CallPort;
import com.hyuk.mock_target.fault.FaultService;
import com.hyuk.mock_target.fault.Faults;
import com.hyuk.mock_target.metrics.SimulatorMetrics;
import com.hyuk.mock_target.simulation.CallSimulator;
import com.hyuk.mock_target.simulation.DuplicateRegistry;
import com.hyuk.mock_target.simulation.PerSecondLimiter;
import com.hyuk.mock_target.simulation.RandomSource;
import com.hyuk.mock_target.simulation.Sleeper;
import com.hyuk.mock_target.simulation.WorkSlots;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableScheduling
public class SimulatorConfig {

    @Bean
    public WorkSlots workSlots(MockTargetProperties properties) {
        MockTargetProperties.TargetSystem spec = properties.selected();
        return new WorkSlots(spec.maxConcurrency(), spec.queueCapacity());
    }

    @Bean
    public Faults faults() {
        return new Faults();
    }

    @Bean
    public DuplicateRegistry duplicateRegistry(MockTargetProperties properties) {
        return new DuplicateRegistry(properties.duplicateRetention(), System::currentTimeMillis);
    }

    @Bean
    public SimulatorMetrics simulatorMetrics(MeterRegistry registry, WorkSlots slots, Faults faults) {
        return new SimulatorMetrics(registry, slots, faults);
    }

    @Bean
    public CallSimulator callSimulator(MockTargetProperties properties, WorkSlots slots, DuplicateRegistry duplicates,
                                       Faults faults, JsonMapper jsonMapper, SimulatorMetrics metrics) {
        MockTargetProperties.TargetSystem spec = properties.selected();

        return new CallSimulator(
                spec,
                new PerSecondLimiter(spec.maxTps(), System::currentTimeMillis),
                slots,
                duplicates,
                faults,
                RandomSource.threadLocal(),
                Sleeper.threadSleep(),
                jsonMapper,
                metrics::recordDuplicate);
    }

    @Bean
    public FaultService faultService(MockTargetProperties properties, Faults faults, WorkSlots slots, CallPort callPort) {
        return new FaultService(faults, slots, callPort, properties.selected().maxConcurrency());
    }

    @Bean
    public DuplicateSweeper duplicateSweeper(DuplicateRegistry duplicates) {
        return new DuplicateSweeper(duplicates);
    }

    public static class DuplicateSweeper {
        private final DuplicateRegistry duplicates;

        public DuplicateSweeper(DuplicateRegistry duplicates) {
            this.duplicates = duplicates;
        }

        @Scheduled(fixedDelay = 10_000)
        public void sweep() {
            duplicates.sweep();
        }
    }
}
