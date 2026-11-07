package com.hyuk.integration_test.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.util.Map;

/** 테스트 사이에 장애와 요청을 멈추고 상태가 정상이 될 때까지 기다려, 앞 테스트의 영향이 섞이지 않게 한다. */
public abstract class IntegrationTestBase {
    protected static final String TARGET = MockTarget.A.id();
    protected static final String CALLS = "sum(flow_control_outbound_calls_total{target=\"" + TARGET + "\"})";

    private static final Duration HEALTHY_TIMEOUT = Duration.ofMinutes(10);
    private static final int SETTLE_RATE = 25;
    private static final int MINIMUM_CALLS = 50;

    @BeforeAll
    static void startEnvironment() {
        IntegrationEnvironment.start();
    }

    @BeforeEach
    void resetBefore() {
        reset();
    }

    @AfterEach
    void resetAfter() {
        MockTarget.all().forEach(MockTarget::clearAllFaults);
        Generator.stop();
    }

    private void reset() {
        MockTarget.all().forEach(MockTarget::clearAllFaults);
        Generator.stop();

        if (!"HEALTHY".equals(Prometheus.stateOf(TARGET))) {
            Generator.start(Map.of(TARGET, SETTLE_RATE), 300);
            Await.until(TARGET + " 상태가 정상으로 돌아옴", HEALTHY_TIMEOUT, () -> {
                if (Generator.isStopped()) {
                    Generator.start(Map.of(TARGET, SETTLE_RATE), 300);
                }
                return "HEALTHY".equals(Prometheus.stateOf(TARGET));
            });
            Generator.stop();
        }

        // 장애 동안 쌓인 요청이 남아 있으면 다음 테스트의 호출 수에 섞이므로 다 처리될 때까지 기다린다.
        Await.until(TARGET + " 쌓인 요청 처리", HEALTHY_TIMEOUT, () -> Kafka.pendingRequests(TARGET) == 0);
    }

    /** 요청이 다시 흘러 저트래픽 모드가 풀리고, 판정에 필요한 호출 수가 모일 때까지 기다린다. */
    protected static void awaitNormalJudgment() {
        Await.until(TARGET + " 일반 판정 모드", Duration.ofMinutes(2), () ->
                Prometheus.value("flow_control_health_mode{target=\"" + TARGET + "\",mode=\"low_traffic\"}") == 0.0
                        && Prometheus.value("flow_control_health_samples{target=\"" + TARGET + "\"}") >= MINIMUM_CALLS
                        && "HEALTHY".equals(Prometheus.stateOf(TARGET)));
    }
}
