package com.hyuk.flow_control.adapter.in.kafka;

import com.hyuk.flow_control.application.port.in.RequestIntakeUseCase;
import com.hyuk.flow_control.domain.request.OutboundRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class KafkaRequestListenerTest {
    private static final String TOPIC = "flow-control.requests.target-a";

    private RequestIntakeUseCase intake;
    private KafkaRequestListener listener;

    @BeforeEach
    void setUp() {
        intake = mock(RequestIntakeUseCase.class);
        listener = new KafkaRequestListener(intake, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("JSON 메시지를 요청으로 바꿔 넘긴다")
    void passesParsedRequest() {
        String json = """
                {"requestId":"req-1","targetSystemId":"target-a","payload":"{\\"amount\\":1000}","createdAt":"2026-10-13T09:00:00.123Z"}
                """;

        listener.onMessage(record(TOPIC, json));

        verify(intake).process(new OutboundRequest(
                "req-1", "target-a", "{\"amount\":1000}", Instant.parse("2026-10-13T09:00:00.123Z")));
    }

    @Test
    @DisplayName("JSON 이 아니면 넘기지 않고 건너뛴다")
    void skipsInvalidJson() {
        listener.onMessage(record(TOPIC, "not-json"));

        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("필수 값이 빠지면 넘기지 않고 건너뛴다")
    void skipsMissingFields() {
        listener.onMessage(record(TOPIC, """
                {"requestId":"req-1","targetSystemId":"target-a","payload":"{}"}
                """));
        listener.onMessage(record(TOPIC, """
                {"targetSystemId":"target-a","payload":"{}","createdAt":"2026-10-13T09:00:00Z"}
                """));

        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("메시지의 대상이 토픽의 대상과 다르면 넘기지 않고 건너뛴다")
    void skipsTargetMismatch() {
        listener.onMessage(record(TOPIC, """
                {"requestId":"req-1","targetSystemId":"target-b","payload":"{}","createdAt":"2026-10-13T09:00:00Z"}
                """));

        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("값이 비어 있는 메시지는 넘기지 않고 건너뛴다")
    void skipsNullValue() {
        listener.onMessage(record(TOPIC, null));

        verifyNoInteractions(intake);
    }

    private static ConsumerRecord<String, String> record(String topic, String value) {
        return new ConsumerRecord<>(topic, 0, 0L, "key", value);
    }
}
