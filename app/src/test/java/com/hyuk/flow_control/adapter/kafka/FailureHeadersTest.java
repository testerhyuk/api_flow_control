package com.hyuk.flow_control.adapter.kafka;

import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.domain.retry.DeadLetterReason;
import com.hyuk.flow_control.domain.retry.DeliveryContext;
import com.hyuk.flow_control.domain.retry.RetryStage;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FailureHeadersTest {
    private static final Instant T1 = Instant.parse("2026-10-13T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-13T09:00:11Z");

    @Test
    @DisplayName("실패 기록을 헤더에 쓰고 다시 읽으면 같은 값이 나온다")
    void roundTrip() {
        RecordHeaders headers = new RecordHeaders();
        DeliveryContext context = new DeliveryContext(RetryStage.THIRD, 3, T1, T2, "flow-control.requests.target-a-2@9");

        FailureHeaders.writeContext(headers, context, OutcomeKind.SERVER_ERROR);

        assertThat(FailureHeaders.readContext(headers, RetryStage.THIRD, "fallback")).isEqualTo(new DeliveryContext(
                RetryStage.THIRD, 3, T1, T2, "flow-control.requests.target-a-2@9", OutcomeKind.SERVER_ERROR));
        assertThat(FailureHeaders.read(headers, FailureHeaders.LAST_KIND)).isEqualTo("SERVER_ERROR");
        assertThat(FailureHeaders.read(headers, FailureHeaders.RETRY_STAGE)).isEqualTo("60s");
    }

    @Test
    @DisplayName("헤더가 없으면 실패 0번, 들어온 위치는 대신 받은 값으로 읽는다")
    void readsDefaultsWithoutHeaders() {
        DeliveryContext context = FailureHeaders.readContext(new RecordHeaders(), RetryStage.FIRST, "topic-0@1");

        assertThat(context).isEqualTo(new DeliveryContext(RetryStage.FIRST, 0, null, null, "topic-0@1"));
    }

    @Test
    @DisplayName("다시 쓰면 같은 이름의 헤더는 하나만 남는다")
    void overwrites() {
        RecordHeaders headers = new RecordHeaders();

        FailureHeaders.writeContext(headers, new DeliveryContext(RetryStage.FIRST, 1, T1, T1, "o"), OutcomeKind.TIMEOUT);
        FailureHeaders.writeContext(headers, new DeliveryContext(RetryStage.SECOND, 2, T1, T2, "o"), OutcomeKind.SERVER_ERROR);

        assertThat(headers.headers(FailureHeaders.FAILED_ATTEMPTS)).hasSize(1);
        assertThat(FailureHeaders.read(headers, FailureHeaders.FAILED_ATTEMPTS)).isEqualTo("2");
        assertThat(FailureHeaders.read(headers, FailureHeaders.LAST_KIND)).isEqualTo("SERVER_ERROR");
    }

    @Test
    @DisplayName("DLQ 이유와 예외 내용을 헤더에 쓴다")
    void writesDeadLetter() {
        RecordHeaders headers = new RecordHeaders();

        FailureHeaders.writeDeadLetter(headers, DeadLetterReason.PROCESSING_ERROR, "IllegalStateException: 오류");

        assertThat(FailureHeaders.read(headers, FailureHeaders.DEAD_LETTER_REASON)).isEqualTo("processing_error");
        assertThat(FailureHeaders.read(headers, FailureHeaders.ERROR)).isEqualTo("IllegalStateException: 오류");
    }

    @Test
    @DisplayName("외부 기관이 보낸 HTTP 상태 코드와 결과 코드를 헤더에 쓴다")
    void writesResponseCodes() {
        RecordHeaders headers = new RecordHeaders();

        FailureHeaders.writeResponseCodes(headers, 200, "E777");

        assertThat(FailureHeaders.read(headers, FailureHeaders.STATUS_CODE)).isEqualTo("200");
        assertThat(FailureHeaders.read(headers, FailureHeaders.RESULT_CODE)).isEqualTo("E777");
    }

    @Test
    @DisplayName("값이 없는 항목은 헤더를 쓰지 않는다")
    void skipsNullValues() {
        RecordHeaders headers = new RecordHeaders();

        FailureHeaders.writeContext(headers, DeliveryContext.first("o"), null);
        FailureHeaders.writeDeadLetter(headers, DeadLetterReason.DLQ_KIND, null);
        FailureHeaders.writeResponseCodes(headers, null, null);

        assertThat(headers.lastHeader(FailureHeaders.FIRST_FAILED_AT)).isNull();
        assertThat(headers.lastHeader(FailureHeaders.LAST_KIND)).isNull();
        assertThat(headers.lastHeader(FailureHeaders.RETRY_STAGE)).isNull();
        assertThat(headers.lastHeader(FailureHeaders.ERROR)).isNull();
        assertThat(headers.lastHeader(FailureHeaders.STATUS_CODE)).isNull();
        assertThat(headers.lastHeader(FailureHeaders.RESULT_CODE)).isNull();
    }
}
