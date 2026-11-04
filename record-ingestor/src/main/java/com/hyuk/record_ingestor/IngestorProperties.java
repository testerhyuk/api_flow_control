package com.hyuk.record_ingestor;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "record-ingestor")
public record IngestorProperties(
        String topicPrefix,
        String requesterId
) {
    public IngestorProperties {
        if (topicPrefix == null || topicPrefix.isBlank()) {
            throw new IllegalArgumentException("topic-prefix 는 필수입니다");
        }
        if (requesterId == null || requesterId.isBlank()) {
            throw new IllegalArgumentException("requester-id 는 필수입니다");
        }
    }

    public String topicOf(String targetSystemId) {
        return topicPrefix + targetSystemId + "." + requesterId;
    }
}
