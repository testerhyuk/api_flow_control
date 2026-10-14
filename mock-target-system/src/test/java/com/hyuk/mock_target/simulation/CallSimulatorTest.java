package com.hyuk.mock_target.simulation;

import com.hyuk.mock_target.config.MockTargetProperties;
import com.hyuk.mock_target.config.ResponseFormat;
import com.hyuk.mock_target.fault.FaultType;
import com.hyuk.mock_target.fault.Faults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CallSimulatorTest {
    private static final String PAYLOAD = "{\"amount\":1000}";

    private final AtomicLong now = new AtomicLong(0);
    private final List<Duration> sleeps = new ArrayList<>();
    private final FixedRandom random = new FixedRandom();
    private final Faults faults = new Faults();
    private final AtomicInteger duplicates = new AtomicInteger();

    private CallSimulator simulator(MockTargetProperties.TargetSystem spec, WorkSlots slots) {
        return new CallSimulator(
                spec,
                new PerSecondLimiter(spec.maxTps(), now::get),
                slots,
                new DuplicateRegistry(Duration.ofMinutes(10), now::get),
                faults,
                random,
                sleeps::add,
                JsonMapper.builder().build(),
                duplicates::incrementAndGet);
    }

    private CallSimulator simulator() {
        return simulator(spec(100, 0.01, 0.02), new WorkSlots(10, 0));
    }

    @Test
    @DisplayName("평소에는 처리시간만큼 기다린 뒤 성공한다")
    void succeedsAfterProcessingTime() throws Exception {
        CallResult result = simulator().handle("req-1", PAYLOAD);

        assertThat(result).isEqualTo(CallResult.of(Outcome.SUCCESS));
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.getFirst().toNanos()).isCloseTo(Duration.ofMillis(100).toNanos() * 1016 / 1000, within(1_000L));
    }

    @Test
    @DisplayName("처리 중인 요청이 많을수록, 흔들림과 느려짐 장애만큼 처리시간이 길어진다")
    void processingTimeIncludesLoadJitterAndSlowFault() throws Exception {
        WorkSlots slots = new WorkSlots(2, 0);
        slots.enter();
        random.gaussian = 1.6448536269514722;
        faults.apply(FaultType.SLOW, 2.0);

        simulator(spec(100, 0, 0), slots).handle("req-1", PAYLOAD);

        assertThat(sleeps.getFirst().toNanos()).isCloseTo(Duration.ofMillis(780).toNanos(), within(1_000L));
    }

    @Test
    @DisplayName("뽑은 값이 서버 오류 비율보다 작으면 서버 오류, 그다음 업무 거절 비율 안이면 업무 거절이다")
    void decidesOutcomeByRates() throws Exception {
        CallSimulator simulator = simulator();

        random.uniform = 0.005;
        assertThat(simulator.handle("req-1", PAYLOAD).outcome()).isEqualTo(Outcome.SERVER_ERROR);

        random.uniform = 0.02;
        assertThat(simulator.handle("req-2", PAYLOAD).outcome()).isEqualTo(Outcome.BUSINESS_REJECTED);

        random.uniform = 0.03;
        assertThat(simulator.handle("req-3", PAYLOAD).outcome()).isEqualTo(Outcome.SUCCESS);
    }

    @Test
    @DisplayName("실패율 증가 장애가 걸리면 서버 오류 비율을 그 값으로 바꾼다")
    void failureRateFaultReplacesServerErrorRate() throws Exception {
        faults.apply(FaultType.FAILURE_RATE, 0.5);
        random.uniform = 0.4;

        assertThat(simulator().handle("req-1", PAYLOAD).outcome()).isEqualTo(Outcome.SERVER_ERROR);
    }

    @Test
    @DisplayName("초당 한도를 넘으면 처리하지 않고 설정한 Retry-After 와 함께 거절한다")
    void rateLimited() throws Exception {
        CallSimulator simulator = simulator(spec(1, 0, 0), new WorkSlots(10, 0));
        simulator.handle("req-1", PAYLOAD);

        CallResult result = simulator.handle("req-2", PAYLOAD);

        assertThat(result).isEqualTo(new CallResult(Outcome.RATE_LIMITED, 10L));
        assertThat(sleeps).hasSize(1);
    }

    @Test
    @DisplayName("점검 중이면 초당 한도에 세지 않고 점검 응답을 보낸다")
    void maintenanceComesFirst() throws Exception {
        CallSimulator simulator = simulator(spec(1, 0, 0), new WorkSlots(10, 0));
        faults.apply(FaultType.MAINTENANCE, 30.0);

        assertThat(simulator.handle("req-1", PAYLOAD)).isEqualTo(new CallResult(Outcome.MAINTENANCE, 30L));
        assertThat(simulator.handle("req-2", PAYLOAD)).isEqualTo(new CallResult(Outcome.MAINTENANCE, 30L));

        faults.clear(FaultType.MAINTENANCE);
        assertThat(simulator.handle("req-3", PAYLOAD).outcome()).isEqualTo(Outcome.SUCCESS);
    }

    @Test
    @DisplayName("요청 ID 가 없거나 본문이 JSON 이 아니면 잘못된 요청이다")
    void rejectsInvalidRequests() throws Exception {
        CallSimulator simulator = simulator();

        assertThat(simulator.handle(null, PAYLOAD).outcome()).isEqualTo(Outcome.INVALID_REQUEST);
        assertThat(simulator.handle("req-1", "not json").outcome()).isEqualTo(Outcome.INVALID_REQUEST);
        assertThat(simulator.handle("req-2", " ").outcome()).isEqualTo(Outcome.INVALID_REQUEST);
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("같은 요청 ID 가 다시 오면 처리하지 않고 처음 결과를 돌려주며 중복으로 센다")
    void returnsFirstResultForDuplicate() throws Exception {
        CallSimulator simulator = simulator();
        simulator.handle("req-1", PAYLOAD);

        random.uniform = 0.0;
        CallResult again = simulator.handle("req-1", PAYLOAD);

        assertThat(again.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(sleeps).hasSize(1);
        assertThat(duplicates.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("자리와 대기열이 모두 차면 과부하로 거절한다")
    void overloadedWhenNoRoom() throws Exception {
        WorkSlots slots = new WorkSlots(1, 0);
        slots.enter();

        assertThat(simulator(spec(100, 0, 0), slots).handle("req-1", PAYLOAD).outcome()).isEqualTo(Outcome.OVERLOADED);
        assertThat(sleeps).isEmpty();
    }

    @Test
    @DisplayName("응답 없음 장애가 걸리면 지정한 시간만큼 붙잡은 뒤 정상 처리한다")
    void noResponseHoldsThenProcesses() throws Exception {
        faults.apply(FaultType.NO_RESPONSE, 10.0);

        CallResult result = simulator().handle("req-1", PAYLOAD);

        assertThat(sleeps.getFirst()).isEqualTo(Duration.ofSeconds(10));
        assertThat(sleeps).hasSize(2);
        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
    }

    @Test
    @DisplayName("처리가 끝나면 자리를 돌려준다")
    void releasesSlot() throws Exception {
        WorkSlots slots = new WorkSlots(1, 0);

        simulator(spec(100, 0, 0), slots).handle("req-1", PAYLOAD);

        assertThat(slots.inUse()).isZero();
    }

    private static MockTargetProperties.TargetSystem spec(int maxTps, double serverErrorRate, double businessRejectRate) {
        return new MockTargetProperties.TargetSystem(
                9001, 9101, 10, 0, 100, 1.5, 2.6, maxTps, Duration.ofSeconds(10),
                serverErrorRate, businessRejectRate, ResponseFormat.STATUS_CODE);
    }

    private static final class FixedRandom implements RandomSource {
        private double gaussian = 0;
        private double uniform = 0.5;

        @Override
        public double nextGaussian() {
            return gaussian;
        }

        @Override
        public double nextDouble() {
            return uniform;
        }
    }
}
