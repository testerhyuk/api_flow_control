package com.hyuk.flow_control.adapter.kafka;

import com.hyuk.flow_control.domain.retry.RetryStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTopicsTest {

    @Test
    @DisplayName("대상 ID 로 요청 토픽 이름을 만든다")
    void requestTopic() {
        assertThat(RequestTopics.of("target-a")).isEqualTo("flow-control.requests.target-a");
    }

    @Test
    @DisplayName("재시도 토픽 이름에는 대기 시간 단계가 들어간다")
    void retryTopic() {
        assertThat(RequestTopics.retry(RetryStage.FIRST, "target-a")).isEqualTo("flow-control.retry-1s.target-a");
        assertThat(RequestTopics.retry(RetryStage.SECOND, "target-a")).isEqualTo("flow-control.retry-10s.target-a");
        assertThat(RequestTopics.retry(RetryStage.THIRD, "target-a")).isEqualTo("flow-control.retry-60s.target-a");
        assertThat(RequestTopics.retry(RetryStage.FOURTH, "target-a")).isEqualTo("flow-control.retry-300s.target-a");
    }

    @Test
    @DisplayName("대상 ID 로 DLQ 토픽 이름을 만든다")
    void deadLetterTopic() {
        assertThat(RequestTopics.deadLetter("target-a")).isEqualTo("flow-control.dlq.target-a");
    }
}
