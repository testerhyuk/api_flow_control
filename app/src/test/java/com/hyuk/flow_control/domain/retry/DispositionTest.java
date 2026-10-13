package com.hyuk.flow_control.domain.retry;

import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class DispositionTest {

    @Test
    @DisplayName("SUCCESS 는 끝낸다")
    void successCompletes() {
        assertThat(Disposition.of(OutcomeKind.SUCCESS)).isEqualTo(Disposition.COMPLETE);
    }

    @Test
    @DisplayName("BUSINESS_REJECTED 는 폐기한다")
    void businessRejectedIsDiscarded() {
        assertThat(Disposition.of(OutcomeKind.BUSINESS_REJECTED)).isEqualTo(Disposition.DISCARD);
    }

    @ParameterizedTest
    @EnumSource(value = OutcomeKind.class, names = {
            "CONNECTION_FAILED", "SERVER_ERROR", "TOO_MANY_REQUESTS", "UNDER_MAINTENANCE", "TIMEOUT", "NETWORK_ERROR"})
    @DisplayName("일시적일 수 있는 실패는 재시도한다")
    void transientFailuresAreRetried(OutcomeKind kind) {
        assertThat(Disposition.of(kind)).isEqualTo(Disposition.RETRY);
    }

    @ParameterizedTest
    @EnumSource(value = OutcomeKind.class, names = {"INVALID_REQUEST", "UNEXPECTED_RESPONSE"})
    @DisplayName("다시 보내도 소용없는 실패는 DLQ 로 보낸다")
    void permanentFailuresGoToDeadLetter(OutcomeKind kind) {
        assertThat(Disposition.of(kind)).isEqualTo(Disposition.DEAD_LETTER);
    }
}
