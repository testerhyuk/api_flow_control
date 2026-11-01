package com.hyuk.flow_control.adapter.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTopicDefinitionsTest {

    @Test
    @DisplayName("외부 서버마다 재시도 4개, DLQ, 만료 토픽을 만들고 복제본 수는 설정한 값이다")
    void definesTopicsWithReplicas() {
        List<NewTopic> topics = RequestTopicDefinitions.forTarget("target-a", 15, 2);

        assertThat(topics).extracting(NewTopic::name).containsExactly(
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

    @Test
    @DisplayName("요청 토픽은 외부 서버의 파티션 수로, 결과 토픽은 파티션 1개로 만든다")
    void definesRequesterTopics() {
        NewTopic request = RequestTopicDefinitions.request("target-a", "loan", 15, 2);
        NewTopic result = RequestTopicDefinitions.result("loan", 2);

        assertThat(request.name()).isEqualTo("flow-control.requests.target-a.loan");
        assertThat(request.numPartitions()).isEqualTo(15);
        assertThat(request.replicationFactor()).isEqualTo((short) 2);
        assertThat(result.name()).isEqualTo("flow-control.results.loan");
        assertThat(result.numPartitions()).isEqualTo(1);
        assertThat(result.replicationFactor()).isEqualTo((short) 2);
    }
}
