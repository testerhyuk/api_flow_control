package com.hyuk.flow_control.adapter.in.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestTopicsTest {

    @Test
    @DisplayName("대상 ID 로 요청 토픽 이름을 만든다")
    void topicOfTarget() {
        assertThat(RequestTopics.of("target-a")).isEqualTo("flow-control.requests.target-a");
    }

    @Test
    @DisplayName("요청 토픽 이름에서 대상 ID 를 꺼낸다")
    void targetOfTopic() {
        assertThat(RequestTopics.targetOf("flow-control.requests.target-a")).isEqualTo("target-a");
    }

    @Test
    @DisplayName("요청 토픽 이름이 아니면 대상 ID 를 꺼낼 수 없다")
    void rejectsOtherTopic() {
        assertThatThrownBy(() -> RequestTopics.targetOf("other.target-a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestTopics.targetOf("flow-control.requests."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestTopics.targetOf(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
