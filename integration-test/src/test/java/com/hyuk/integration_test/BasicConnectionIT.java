package com.hyuk.integration_test;

import com.hyuk.integration_test.support.Await;
import com.hyuk.integration_test.support.Generator;
import com.hyuk.integration_test.support.IntegrationEnvironment;
import com.hyuk.integration_test.support.IntegrationTestBase;
import com.hyuk.integration_test.support.Kafka;
import com.hyuk.integration_test.support.MockTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Order(1)
@DisplayName("기본 연결")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BasicConnectionIT extends IntegrationTestBase {
    private static final String REQUESTER = "record-ingestor";
    private static final String RESULT_TOPIC = "flow-control.results." + REQUESTER;
    private static final int RATE = 25;
    private static final long DURATION_SECONDS = 15;
    private static final Set<String> SERVICES = Set.of(
            "app", "mock-target-a", "mock-target-b", "mock-target-c", "record-ingestor",
            "kafka-1", "kafka-2", "kafka-3", "postgres", "redis", "prometheus", "grafana", "kafka-ui");

    @Test
    @Order(1)
    @DisplayName("13개 컨테이너가 모두 실행 중이다")
    void allContainersAreUp() {
        assertAllContainersUp();
    }

    @Test
    @Order(2)
    @DisplayName("app 컨테이너가 시작할 때 토픽 22개가 모두 만들어져 있다")
    void allTopicsAreCreated() {
        Set<String> expected = expectedTopics();
        assertThat(expected).hasSize(22);

        Await.until("토픽 22개 생성", Duration.ofMinutes(2),
                () -> flowControlTopics().containsAll(expected));
        assertThat(flowControlTopics()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @Order(3)
    @DisplayName("보낸 요청이 요청 토픽에 모두 들어가고, 같은 건수의 결과가 결과 토픽에 도착한다")
    void requestsReachResultTopic() {
        Map<MockTarget, Long> requestsBefore = new LinkedHashMap<>();
        MockTarget.all().forEach(target -> requestsBefore.put(target, Kafka.messageCount(requestTopic(target))));
        long resultsBefore = Kafka.messageCount(RESULT_TOPIC);

        Map<String, Integer> rates = new LinkedHashMap<>();
        MockTarget.all().forEach(target -> rates.put(target.id(), RATE));
        Generator.start(rates, DURATION_SECONDS);
        Await.until("요청 전송 끝남", Duration.ofSeconds(DURATION_SECONDS + 60), Generator::isStopped);

        long totalSent = 0;
        for (MockTarget target : MockTarget.all()) {
            Generator.TargetStatus status = Generator.targetStatus(target.id());

            assertThat(status.sent()).as("%s 전송 건수", target.id()).isPositive();
            assertThat(status.failed()).as("%s 전송 실패 건수", target.id()).isZero();
            assertThat(Kafka.messageCount(requestTopic(target)) - requestsBefore.get(target))
                    .as("%s 요청 토픽에 들어간 건수", target.id())
                    .isEqualTo(status.sent());
            totalSent += status.sent();
        }

        long expectedResults = totalSent;
        Await.until("결과 " + expectedResults + "건 도착", Duration.ofMinutes(3),
                () -> Kafka.messageCount(RESULT_TOPIC) - resultsBefore >= expectedResults);
        assertThat(Kafka.messageCount(RESULT_TOPIC) - resultsBefore).as("결과 토픽 건수").isEqualTo(expectedResults);
    }

    static void assertAllContainersUp() {
        List<JsonNode> containers = IntegrationEnvironment.composeContainers();

        assertThat(containers).as("컨테이너 수").hasSize(SERVICES.size());
        Set<String> services = new HashSet<>();
        for (JsonNode container : containers) {
            String service = container.path("Service").asString();
            services.add(service);
            assertThat(container.path("State").asString()).as("%s 상태", service).isEqualTo("running");
        }
        assertThat(services).containsExactlyInAnyOrderElementsOf(SERVICES);
    }

    private static Set<String> flowControlTopics() {
        Set<String> topics = new HashSet<>(Kafka.topicNames());
        topics.removeIf(topic -> !topic.startsWith("flow-control."));
        return topics;
    }

    private static Set<String> expectedTopics() {
        Set<String> topics = new HashSet<>();
        topics.add(RESULT_TOPIC);
        for (MockTarget target : MockTarget.all()) {
            topics.add(requestTopic(target));
            topics.add("flow-control.dlq." + target.id());
            topics.add("flow-control.expired." + target.id());
            for (String delay : List.of("1s", "10s", "60s", "300s")) {
                topics.add("flow-control.retry-" + delay + "." + target.id());
            }
        }
        return topics;
    }

    private static String requestTopic(MockTarget target) {
        return "flow-control.requests." + target.id() + "." + REQUESTER;
    }
}
