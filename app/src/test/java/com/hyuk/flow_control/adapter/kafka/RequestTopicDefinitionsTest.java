package com.hyuk.flow_control.adapter.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTopicDefinitionsTest {

    @Test
    @DisplayName("대상마다 요청, 재시도 4개, DLQ, 만료 토픽을 만들고 복제본 수는 설정한 값이다")
    void definesTopicsWithReplicas() {
        List<NewTopic> topics = RequestTopicDefinitions.forTarget("target-a", 15, 2);

        assertThat(topics).extracting(NewTopic::name).containsExactly(
                "flow-control.requests.target-a",
                "flow-control.retry-1s.target-a",
                "flow-control.retry-10s.target-a",
                "flow-control.retry-60s.target-a",
                "flow-control.retry-300s.target-a",
                "flow-control.dlq.target-a",
                "flow-control.expired.target-a");
        assertThat(topics).allMatch(topic -> topic.replicationFactor() == 2);
        assertThat(topics.getFirst().numPartitions()).isEqualTo(15);
        assertThat(topics.getLast().numPartitions()).isEqualTo(1);
    }
}
