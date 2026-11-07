package com.hyuk.integration_test;

import com.hyuk.integration_test.support.Await;
import com.hyuk.integration_test.support.Generator;
import com.hyuk.integration_test.support.Http;
import com.hyuk.integration_test.support.IntegrationEnvironment;
import com.hyuk.integration_test.support.IntegrationTestBase;
import com.hyuk.integration_test.support.MockTarget;
import com.hyuk.integration_test.support.Prometheus;
import com.hyuk.integration_test.support.TestTimeline;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Order(5)
@DisplayName("인프라와 대시보드")
class InfrastructureIT extends IntegrationTestBase {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int RATE = 20;
    private static final long DURATION_SECONDS = 300;
    private static final double RATE_TOLERANCE = 2.0;
    private static final String CALLS_PER_SECOND = "sum(rate(flow_control_outbound_calls_total{target=\"" + TARGET + "\"}[1m]))";
    private static final Duration RATE_WINDOW = Duration.ofSeconds(70);
    private static final Duration RECORD_MARGIN = Duration.ofSeconds(2);
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(2);
    private static final String GRAFANA_DASHBOARD = "http://localhost:3000/api/dashboards/uid/flow-control";
    private static final Map<String, String> GRAFANA_AUTH = Map.of("Authorization", "Basic "
            + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8)));

    @Test
    @DisplayName("지표가 중복 없이 수집되어 저장되고, 알림 규칙과 대시보드가 올라와 있다")
    void metricsAlertsAndDashboardAreInPlace() {
        Generator.start(Map.of(TARGET, RATE), DURATION_SECONDS);
        awaitNormalJudgment();

        // 1분 구간이 전부 초당 20건으로 채워진 뒤 본다.
        Await.sleep(RATE_WINDOW);
        assertThat(Prometheus.value(CALLS_PER_SECOND)).as("초당 호출 수").isCloseTo(RATE, within(RATE_TOLERANCE));

        Instant recordedAt = Instant.now().minus(RECORD_MARGIN);
        double recordedCalls = Prometheus.valueAt(CALLS, recordedAt);
        assertThat(recordedCalls).as("기록한 호출 건수").isPositive();

        IntegrationEnvironment.recreatePrometheus();
        Await.until("Prometheus 준비", READY_TIMEOUT, Prometheus::isReady);
        assertThat(Prometheus.valueAt(CALLS, recordedAt)).as("새로 만든 뒤 같은 시각의 호출 건수").isEqualTo(recordedCalls);

        Await.until("수집 대상 4개가 모두 up", READY_TIMEOUT, InfrastructureIT::allTargetsUp);
        assertThat(scrapeInstances()).containsExactlyInAnyOrder(
                "app:8081", MockTarget.A.instance(), MockTarget.B.instance(), MockTarget.C.instance());

        assertThat(Prometheus.alertRuleNames()).containsExactlyInAnyOrder(
                "PolicyRefreshFailing", "InvalidPolicyRemaining", "PolicyApplyFailing",
                "DeadLetterIncreasing", "RequestsExpiring");

        JsonNode dashboard = JSON.readTree(Http.get(GRAFANA_DASHBOARD, GRAFANA_AUTH));
        assertThat(dashboard.path("dashboard").path("title").asString()).isEqualTo("Flow Control");

        // 마지막 테스트이므로 테스트 동안 죽은 컨테이너가 없는지 여기서 다시 본다.
        BasicConnectionIT.assertAllContainersUp();
    }

    @AfterAll
    static void printManualChecks() {
        TestTimeline.printAll();
        if (!IntegrationEnvironment.isStarted()) {
            return;
        }
        if (IntegrationEnvironment.keepContainers()) {
            System.out.println("""
                    사람이 직접 확인할 것 (http://localhost:3000, admin/admin, 대시보드 Flow Control, 외부 서버 target-a) :
                    - 패널 값 : 상태 변화, 점검 구간, 붙잡힌 요청 패널에 값이 나오고 범례가 문서 용어로 보이는지
                    - 표시 형식 : 상태 변화의 상태별 색이 다르고, 실패율 기준선이 점선인지""");
            IntegrationEnvironment.waitForManualChecks();
        } else {
            System.out.println("화면 확인 항목은 ./gradlew integrationTest -PkeepContainers 로 돌린 뒤 직접 확인합니다.");
        }
    }

    private static boolean allTargetsUp() {
        JsonNode targets = Prometheus.targets();
        boolean allUp = true;
        for (JsonNode target : targets) {
            allUp &= "up".equals(target.path("health").asString());
        }
        return targets.size() == 4 && allUp;
    }

    private static List<String> scrapeInstances() {
        List<String> instances = new ArrayList<>();
        for (JsonNode target : Prometheus.targets()) {
            instances.add(target.path("labels").path("instance").asString());
        }
        return instances;
    }
}
