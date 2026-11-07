package com.hyuk.integration_test;

import com.hyuk.integration_test.support.Await;
import com.hyuk.integration_test.support.Generator;
import com.hyuk.integration_test.support.IntegrationTestBase;
import com.hyuk.integration_test.support.MockTarget;
import com.hyuk.integration_test.support.Prometheus;
import com.hyuk.integration_test.support.TestTimeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Order(4)
@DisplayName("응답 없음")
class NoResponseIT extends IntegrationTestBase {
    private static final String FAULT = "no-response";
    private static final double HOLD_SECONDS = 5;
    private static final int RATE = 25;
    private static final long DURATION_SECONDS = 300;
    private static final Duration FAULT_DURATION = Duration.ofSeconds(20);
    private static final Duration READ_TIMEOUT_MARGIN = Duration.ofMillis(2500);
    private static final Duration SETTLE = Duration.ofSeconds(3);
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(1);
    private static final int WORK_SLOTS = 60;
    private static final int QUEUE_CAPACITY = 500;
    private static final String TIMEOUTS = callsOf("TIMEOUT");
    private static final String SERVER_ERRORS = callsOf("SERVER_ERROR");
    private static final String INSTANCE = "instance=\"" + MockTarget.A.instance() + "\"";
    private static final String OVERLOADED = "mock_target_requests_total{" + INSTANCE + ",outcome=\"OVERLOADED\"}";
    private static final String HELD = "mock_target_held{" + INSTANCE + "}";
    private static final String INFLIGHT = "mock_target_inflight{" + INSTANCE + "}";
    private static final String QUEUE = "mock_target_queue{" + INSTANCE + "}";

    @Test
    @DisplayName("응답이 없으면 호출마다 3초를 기다린 뒤 서버 오류가 아닌 timeout으로 판단한다")
    void judgesNoResponseAsTimeout() {
        Generator.start(Map.of(TARGET, RATE), DURATION_SECONDS);
        awaitNormalJudgment();
        double timeoutsBefore = Prometheus.value(TIMEOUTS);

        long faultStartedAt = System.nanoTime();
        double serverErrors;
        double overloaded;
        double maxHeld = 0;
        double maxInflight = 0;
        double maxQueue = 0;

        TestTimeline timeline = new TestTimeline("응답 없음");
        try {
            timeline.add(Instant.now(), "장애 시작");
            MockTarget.A.applyFault(FAULT, HOLD_SECONDS);

            Await.until("timeout 증가", Duration.ofSeconds(15), () -> Prometheus.value(TIMEOUTS) > timeoutsBefore);
            timeline.add(Instant.now(), "첫 timeout");
            assertThat(Duration.ofNanos(System.nanoTime() - faultStartedAt))
                    .as("첫 timeout 까지 걸린 시간")
                    .isGreaterThanOrEqualTo(READ_TIMEOUT_MARGIN);

            // 장애를 걸기 전에 보낸 호출의 응답이 지표에 잡히고 나서 기준값을 잡는다.
            Await.sleep(SETTLE);
            double serverErrorsBefore = Prometheus.value(SERVER_ERRORS);
            double overloadedBefore = Prometheus.value(OVERLOADED);

            long deadline = faultStartedAt + FAULT_DURATION.toNanos();
            while (System.nanoTime() < deadline) {
                maxHeld = Math.max(maxHeld, Prometheus.value(HELD));
                maxInflight = Math.max(maxInflight, Prometheus.value(INFLIGHT));
                maxQueue = Math.max(maxQueue, Prometheus.value(QUEUE));
                Await.sleep(SAMPLE_INTERVAL);
            }

            serverErrors = Prometheus.value(SERVER_ERRORS) - serverErrorsBefore;
            overloaded = Prometheus.value(OVERLOADED) - overloadedBefore;
            timeline.add(Instant.now(), "장애 해제");
            MockTarget.A.clearFault(FAULT);
        } finally {
            timeline.finish();
        }

        System.out.println("붙잡힌 요청 최대 " + maxHeld + ", 처리 자리 최대 " + maxInflight + ", 대기열 최대 " + maxQueue);
        assertThat(serverErrors).as("장애 동안 늘어난 서버 오류").isZero();
        assertThat(overloaded).as("장애 동안 늘어난 과부하 503").isZero();
        assertThat(maxHeld).as("붙잡힌 요청").isPositive();
        assertThat(maxInflight).as("처리 자리").isLessThan(WORK_SLOTS);
        assertThat(maxQueue).as("대기열").isLessThan(QUEUE_CAPACITY);
    }

    private static String callsOf(String kind) {
        return "flow_control_outbound_calls_total{target=\"" + TARGET + "\",kind=\"" + kind + "\"}";
    }
}
