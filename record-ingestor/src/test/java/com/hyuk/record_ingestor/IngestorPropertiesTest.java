package com.hyuk.record_ingestor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

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
                        "record-ingestor.targets.target-a.rate=100",
                        "record-ingestor.targets.target-b.rate=2.5")
                .run(context -> assertThat(context.getBean(IngestorProperties.class)).isEqualTo(
                        new IngestorProperties("flow-control.requests.", Map.of(
                                "target-a", new IngestorProperties.Target(100),
                                "target-b", new IngestorProperties.Target(2.5)))));
    }

    @Test
    @DisplayName("설정 값이 잘못되면 앱이 뜨지 않는다")
    void failsToStartWithInvalidValue() {
        contextRunner
                .withPropertyValues(
                        "record-ingestor.topic-prefix=flow-control.requests.",
                        "record-ingestor.targets.target-a.rate=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("토픽 접두어가 없으면 만들 수 없다")
    void rejectsBlankTopicPrefix() {
        Map<String, IngestorProperties.Target> targets = Map.of("target-a", new IngestorProperties.Target(1));

        assertThatThrownBy(() -> new IngestorProperties(null, targets))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestorProperties(" ", targets))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("대상이 하나도 없으면 만들 수 없다")
    void rejectsEmptyTargets() {
        assertThatThrownBy(() -> new IngestorProperties("p.", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestorProperties("p.", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rate 가 0 이하면 만들 수 없다")
    void rejectsNonPositiveRate() {
        assertThatThrownBy(() -> new IngestorProperties.Target(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestorProperties.Target(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration
    @EnableConfigurationProperties(IngestorProperties.class)
    static class TestConfig {
    }
}
