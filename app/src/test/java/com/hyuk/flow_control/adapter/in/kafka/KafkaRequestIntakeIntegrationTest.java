package com.hyuk.flow_control.adapter.in.kafka;

import com.hyuk.flow_control.application.port.in.OutboundCallUseCase;
import com.hyuk.flow_control.domain.request.CallAttempt;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.server.port=0"
})
@EmbeddedKafka
class KafkaRequestIntakeIntegrationTest {
    private static final OutboundResult RESULT =
            new OutboundResult(OutcomeKind.SUCCESS, Duration.ofMillis(120), null, null);

    @Autowired
    private EmbeddedKafkaBroker broker;

    @MockitoBean
    private OutboundCallUseCase outboundCall;

    @Test
    @DisplayName("앱이 뜨면 대상별 요청 토픽을 처음 concurrency 만큼의 파티션으로 만든다")
    void createsTopicsWithConcurrencyPartitions() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            Map<String, TopicDescription> topics = admin.describeTopics(List.of(
                    "flow-control.requests.target-a",
                    "flow-control.requests.target-b",
                    "flow-control.requests.target-c")).allTopicNames().get();

            assertThat(topics.get("flow-control.requests.target-a").partitions()).hasSize(10);
            assertThat(topics.get("flow-control.requests.target-b").partitions()).hasSize(15);
            assertThat(topics.get("flow-control.requests.target-c").partitions()).hasSize(19);
        }
    }

    @Test
    @DisplayName("토픽에 넣은 요청을 읽어 외부 호출 서비스로 넘긴다")
    void consumesRequestFromTopic() {
        when(outboundCall.send(any())).thenReturn(new CallAttempt.Called(RESULT));

        send("flow-control.requests.target-b", "req-intake-1", """
                {"requestId":"req-intake-1","targetSystemId":"target-b","payload":"{\\"amount\\":1000}","createdAt":"2026-10-13T09:00:00Z"}
                """);

        ArgumentCaptor<OutboundRequest> captor = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(outboundCall, timeout(15000)).send(captor.capture());

        assertThat(captor.getValue()).isEqualTo(new OutboundRequest(
                "req-intake-1", "target-b", "{\"amount\":1000}", Instant.parse("2026-10-13T09:00:00Z")));
    }

    @Test
    @DisplayName("한도에 막힌 요청은 통과할 때까지 다시 보낸다")
    void retriesBlockedRequestFromTopic() {
        when(outboundCall.send(any())).thenReturn(
                new CallAttempt.BlockedBySemaphore(),
                new CallAttempt.BlockedByTokenBucket(Duration.ofMillis(20)),
                new CallAttempt.Called(RESULT));

        send("flow-control.requests.target-c", "req-intake-2", """
                {"requestId":"req-intake-2","targetSystemId":"target-c","payload":"{}","createdAt":"2026-10-13T09:00:00Z"}
                """);

        verify(outboundCall, timeout(15000).times(3)).send(any());
    }

    @Test
    @DisplayName("대상별 읽기 스레드 수는 처음 concurrency 와 같다")
    void containerConcurrencyMatchesPolicy(@Autowired KafkaRequestIntakeContainers containers) {
        assertThat(containers.concurrencyByTopic()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "flow-control.requests.target-a", 10,
                "flow-control.requests.target-b", 15,
                "flow-control.requests.target-c", 19));
        assertThat(containers.isRunning()).isTrue();
    }

    private void send(String topic, String key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, value.strip()));
            producer.flush();
        }
    }
}
