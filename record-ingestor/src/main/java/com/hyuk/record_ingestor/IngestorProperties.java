package com.hyuk.record_ingestor;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "record-ingestor")
public record IngestorProperties(
        String topicPrefix,
        Map<String, Target> targets
) {
    public IngestorProperties {
        if (topicPrefix == null || topicPrefix.isBlank()) {
            throw new IllegalArgumentException("topic-prefix 는 필수입니다");
        }
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("요청을 보낼 대상이 하나 이상 있어야 합니다");
        }
        targets = Map.copyOf(targets);
    }

    public record Target(double rate) {
        public Target {
            if (rate <= 0) {
                throw new IllegalArgumentException("rate 는 0보다 커야 합니다");
            }
        }
    }
}
