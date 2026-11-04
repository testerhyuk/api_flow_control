package com.hyuk.record_ingestor;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaTopicCheckerTest {
    @Test
    @DisplayName("Kafka 가 응답하지 않으면 확인하지 못했다고 거부한다")
    void rejectsWhenKafkaUnavailable() {
        KafkaTopicChecker checker = new KafkaTopicChecker(
                new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1")));

        assertThatThrownBy(() -> checker.requireExisting(List.of("flow-control.requests.target-a.record-ingestor")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka 를 확인하지 못했습니다");
    }
}
