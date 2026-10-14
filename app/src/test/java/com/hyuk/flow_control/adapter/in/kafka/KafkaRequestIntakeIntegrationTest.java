package com.hyuk.flow_control.adapter.in.kafka;

import com.hyuk.flow_control.adapter.kafka.FailureHeaders;
import com.hyuk.flow_control.application.port.in.OutboundCallUseCase;
import com.hyuk.flow_control.domain.request.CallAttempt;
import com.hyuk.flow_control.domain.request.OutboundResult;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.support.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "management.server.port=0"
})
@EmbeddedKafka
@Import(TestcontainersConfiguration.class)
class KafkaRequestIntakeIntegrationTest {
    private static final OutboundResult SUCCESS =
            new OutboundResult(OutcomeKind.SUCCESS, Duration.ofMillis(120), null, null, null, null);

    @Autowired
    private EmbeddedKafkaBroker broker;

    @MockitoBean
    private OutboundCallUseCase outboundCall;

    @Test
    @DisplayName("앱이 뜨면 대상별 요청 토픽과 재시도 토픽 4개는 처음 concurrency 만큼, DLQ 토픽과 만료 토픽은 1개 파티션으로 만든다")
    void createsTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            Map<String, Integer> expected = new HashMap<>();
            Map.of("target-a", 15, "target-b", 15, "target-c", 15).forEach((target, partitions) -> {
                expected.put("flow-control.requests." + target, partitions);
                for (String stage : List.of("1s", "10s", "60s", "300s")) {
                    expected.put("flow-control.retry-" + stage + "." + target, partitions);
                }
                expected.put("flow-control.dlq." + target, 1);
                expected.put("flow-control.expired." + target, 1);
            });

            Map<String, TopicDescription> topics = admin.describeTopics(expected.keySet()).allTopicNames().get();

            expected.forEach((topic, partitions) ->
                    assertThat(topics.get(topic).partitions()).as(topic).hasSize(partitions));
        }
    }

    @Test
    @DisplayName("대상별로 요청 토픽과 재시도 토픽마다 처음 concurrency 만큼 읽는 스레드를 둔다")
    void containerConcurrency(@Autowired KafkaRequestIntakeContainers containers) {
        Map<String, Integer> concurrency = containers.concurrencyByTopic();

        assertThat(concurrency).hasSize(15);
        assertThat(concurrency.get("flow-control.requests.target-a")).isEqualTo(15);
        assertThat(concurrency.get("flow-control.retry-300s.target-c")).isEqualTo(15);
        assertThat(containers.isRunning()).isTrue();
    }

    @Test
    @DisplayName("토픽에 넣은 요청을 읽어 외부 호출 서비스로 넘긴다")
    void consumesRequest() {
        String requestId = uniqueId();
        when(outboundCall.send(argThat(r -> r != null && requestId.equals(r.requestId()))))
                .thenReturn(new CallAttempt.Called(SUCCESS));

        send("flow-control.requests.target-b", requestId, json(requestId, "target-b"));

        verify(outboundCall, timeout(15000)).send(argThat(r -> r != null && requestId.equals(r.requestId())));
    }

    @Test
    @DisplayName("한도에 막힌 요청은 통과할 때까지 다시 보낸다")
    void retriesBlockedRequest() {
        String requestId = uniqueId();
        when(outboundCall.send(argThat(r -> r != null && requestId.equals(r.requestId())))).thenReturn(
                new CallAttempt.BlockedBySemaphore(),
                new CallAttempt.BlockedByTokenBucket(Duration.ofMillis(20)),
                new CallAttempt.Called(SUCCESS));

        send("flow-control.requests.target-c", requestId, json(requestId, "target-c"));

        verify(outboundCall, timeout(15000).times(3)).send(argThat(r -> r != null && requestId.equals(r.requestId())));
    }

    @Test
    @DisplayName("TIMEOUT 으로 실패한 요청은 1초 재시도 토픽을 거쳐 다시 보내진다")
    void retriesFailedRequestThroughRetryTopic() {
        String requestId = uniqueId();
        when(outboundCall.send(argThat(r -> r != null && requestId.equals(r.requestId())))).thenReturn(
                new CallAttempt.Called(new OutboundResult(OutcomeKind.TIMEOUT, Duration.ofSeconds(3), null, null, null, null)),
                new CallAttempt.Called(SUCCESS));

        send("flow-control.requests.target-a", requestId, json(requestId, "target-a"));

        verify(outboundCall, timeout(20000).times(2)).send(argThat(r -> r != null && requestId.equals(r.requestId())));
    }

    @Test
    @DisplayName("INVALID_REQUEST 로 실패한 요청은 원래 JSON 과 이유 헤더를 담아 DLQ 로 간다")
    void deadLettersInvalidRequest() {
        String requestId = uniqueId();
        when(outboundCall.send(argThat(r -> r != null && requestId.equals(r.requestId())))).thenReturn(
                new CallAttempt.Called(new OutboundResult(OutcomeKind.INVALID_REQUEST, Duration.ofMillis(50), null, null, null, null)));
        String value = json(requestId, "target-b");

        send("flow-control.requests.target-b", requestId, value);

        ConsumerRecord<String, String> dead = awaitRecord("flow-control.dlq.target-b", requestId);
        assertThat(dead.value()).contains("\"requestId\":\"" + requestId + "\"");
        assertThat(FailureHeaders.read(dead.headers(), FailureHeaders.DEAD_LETTER_REASON)).isEqualTo("dlq_kind");
        assertThat(FailureHeaders.read(dead.headers(), FailureHeaders.LAST_KIND)).isEqualTo("INVALID_REQUEST");
        assertThat(FailureHeaders.read(dead.headers(), FailureHeaders.FAILED_ATTEMPTS)).isEqualTo("1");
        assertThat(FailureHeaders.read(dead.headers(), FailureHeaders.ORIGIN)).startsWith("flow-control.requests.target-b-");
    }

    @Test
    @DisplayName("유효기간이 지난 요청은 호출하지 않고 만료 토픽으로 간다")
    void expiresOldRequest() {
        String requestId = uniqueId();
        String value = "{\"requestId\":\"" + requestId + "\",\"targetSystemId\":\"target-a\""
                + ",\"payload\":\"{}\",\"createdAt\":\"" + Instant.now().minus(Duration.ofMinutes(11)) + "\"}";

        send("flow-control.requests.target-a", requestId, value);

        ConsumerRecord<String, String> expired = awaitRecord("flow-control.expired.target-a", requestId);
        assertThat(FailureHeaders.read(expired.headers(), FailureHeaders.EXPIRED_WHERE)).isEqualTo("intake");
        assertThat(FailureHeaders.read(expired.headers(), FailureHeaders.EXPIRY_REASON)).isEqualTo("ttl_exceeded");
        verify(outboundCall, never()).send(argThat(r -> r != null && requestId.equals(r.requestId())));
    }

    @Test
    @DisplayName("대상별로 요청 토픽과 재시도 토픽 읽기를 함께 멈췄다가 다시 시작한다")
    void pausesAndResumesTarget(@Autowired KafkaRequestIntakeContainers containers) {
        containers.pause("target-c");
        assertThat(containers.isPaused("target-c")).isTrue();
        assertThat(containers.isPaused("target-b")).isFalse();

        containers.resume("target-c");
        assertThat(containers.isPaused("target-c")).isFalse();
    }

    @Test
    @DisplayName("대상별로 요청 토픽과 재시도 토픽의 컨슈머 랙을 지표로 낸다")
    void exposesConsumerLag(@Autowired MeterRegistry registry) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        double lag = Double.NaN;

        while (Double.isNaN(lag) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            lag = registry.find("flow.control.intake.lag").tags("target", "target-a", "stage", "request")
                    .gauges().stream().findFirst().map(gauge -> gauge.value()).orElse(Double.NaN);
        }

        assertThat(lag).isNotNaN().isGreaterThanOrEqualTo(0.0);
        assertThat(registry.find("flow.control.intake.lag").tags("target", "target-a", "stage", "300s").gauge())
                .isNotNull();
    }

    @Test
    @DisplayName("JSON 이 아닌 메시지는 원래 값 그대로 DLQ 로 간다")
    void deadLettersInvalidMessage() {
        String marker = "not-json-" + uniqueId();

        send("flow-control.requests.target-c", null, marker);

        ConsumerRecord<String, String> dead = awaitRecordWithValue("flow-control.dlq.target-c", marker);
        assertThat(FailureHeaders.read(dead.headers(), FailureHeaders.DEAD_LETTER_REASON)).isEqualTo("invalid_message");
        verify(outboundCall, never()).send(argThat(r -> r != null && marker.equals(r.requestId())));
    }

    private static String uniqueId() {
        return UUID.randomUUID().toString();
    }

    private static String json(String requestId, String target) {
        return "{\"requestId\":\"" + requestId + "\",\"targetSystemId\":\"" + target
                + "\",\"payload\":\"{}\",\"createdAt\":\"" + Instant.now() + "\"}";
    }

    private void send(String topic, String key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, key, value));
            producer.flush();
        }
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        return await(topic, record -> key.equals(record.key()));
    }

    private ConsumerRecord<String, String> awaitRecordWithValue(String topic, String value) {
        return await(topic, record -> value.equals(record.value()));
    }

    private ConsumerRecord<String, String> await(String topic, Predicate<ConsumerRecord<String, String>> match) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + uniqueId(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 20_000;

            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (match.test(record)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("메시지를 찾지 못했습니다 : " + topic);
    }
}
