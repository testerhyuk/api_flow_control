package com.hyuk.record_ingestor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestMessageFactoryTest {
    private final RequestMessageFactory factory = new RequestMessageFactory();

    @Test
    @DisplayName("대상 ID, UUID 요청 ID, 만든 시각을 담는다")
    void holdsTargetIdAndTime() {
        Instant before = Instant.now();
        RequestMessage message = factory.create("target-a");
        Instant after = Instant.now();

        assertThat(message.targetSystemId()).isEqualTo("target-a");
        assertThat(UUID.fromString(message.requestId()).toString()).isEqualTo(message.requestId());
        assertThat(message.createdAt()).isBetween(before, after);
    }

    @Test
    @DisplayName("요청 ID 는 매번 다르다")
    void requestIdIsUnique() {
        assertThat(factory.create("target-a").requestId()).isNotEqualTo(factory.create("target-a").requestId());
    }

    @Test
    @DisplayName("본문은 계좌 번호 10자리와 1000원 단위 금액을 담은 JSON 이다")
    void payloadIsJson() {
        JsonNode payload = JsonMapper.builder().build().readTree(factory.create("target-a").payload());

        assertThat(payload.get("accountNo").asString()).hasSize(10).containsOnlyDigits();
        assertThat(payload.get("amount").asLong()).isBetween(1_000L, 1_000_000L);
        assertThat(payload.get("amount").asLong() % 1_000).isZero();
    }
}
