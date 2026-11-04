package com.hyuk.record_ingestor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestorPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("설정 파일의 값이 그대로 들어온다")
    void bindsFromProperties() {
        contextRunner
                .withPropertyValues(
                        "record-ingestor.topic-prefix=flow-control.requests.",
                        "record-ingestor.requester-id=record-ingestor")
                .run(context -> assertThat(context.getBean(IngestorProperties.class)).isEqualTo(
                        new IngestorProperties("flow-control.requests.", "record-ingestor")));
    }

    @Test
    @DisplayName("설정 값이 없으면 앱이 뜨지 않는다")
    void failsToStartWithoutValue() {
        contextRunner
                .withPropertyValues("record-ingestor.topic-prefix=flow-control.requests.")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("토픽 접두어가 없으면 만들 수 없다")
    void rejectsBlankTopicPrefix() {
        assertThatThrownBy(() -> new IngestorProperties(null, "record-ingestor"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestorProperties(" ", "record-ingestor"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("요청자 ID 가 없으면 만들 수 없다")
    void rejectsBlankRequesterId() {
        assertThatThrownBy(() -> new IngestorProperties("p.", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestorProperties("p.", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("요청 토픽은 접두어 + 외부 서버 + 요청자 ID 다")
    void namesTopicWithRequester() {
        IngestorProperties properties = new IngestorProperties("flow-control.requests.", "record-ingestor");

        assertThat(properties.topicOf("target-a")).isEqualTo("flow-control.requests.target-a.record-ingestor");
    }

    @Configuration
    @EnableConfigurationProperties(IngestorProperties.class)
    static class TestConfig {
    }
}
