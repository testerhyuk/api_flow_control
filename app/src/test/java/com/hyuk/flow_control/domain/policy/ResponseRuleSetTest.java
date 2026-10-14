package com.hyuk.flow_control.domain.policy;

import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponseRuleSetTest {
    private static final Map<String, OutcomeKind> STATUS_CODES = Map.of(
            "2xx", OutcomeKind.SUCCESS,
            "429", OutcomeKind.TOO_MANY_REQUESTS);

    @Test
    @DisplayName("상태 코드 표가 없거나 비어 있으면 만들 수 없다")
    void rejectsMissingStatusCodes() {
        assertThatThrownBy(() -> new ResponseRuleSet(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResponseRuleSet(Map.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"20", "2000", "6xx", "2x", "abc", "0xx"})
    @DisplayName("상태 코드 형식이 세 자리 숫자나 백 단위 묶음이 아니면 만들 수 없다")
    void rejectsInvalidStatusCode(String code) {
        assertThatThrownBy(() -> new ResponseRuleSet(Map.of(code, OutcomeKind.SUCCESS), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상태 코드 표의 결과가 비어 있으면 만들 수 없다")
    void rejectsNullStatusKind() {
        Map<String, OutcomeKind> codes = new HashMap<>();
        codes.put("200", null);

        assertThatThrownBy(() -> new ResponseRuleSet(codes, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결과 코드 위치와 결과 코드 표는 함께 적어야 한다")
    void requiresResultFieldAndCodesTogether() {
        assertThatThrownBy(() -> new ResponseRuleSet(STATUS_CODES, "/resultCode", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResponseRuleSet(STATUS_CODES, null, Map.of("0000", OutcomeKind.SUCCESS)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결과 코드 위치가 / 로 시작하지 않으면 만들 수 없다")
    void rejectsResultFieldWithoutSlash() {
        assertThatThrownBy(() -> new ResponseRuleSet(STATUS_CODES, "resultCode", Map.of("0000", OutcomeKind.SUCCESS)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결과 코드 표의 결과가 비어 있으면 만들 수 없다")
    void rejectsNullResultKind() {
        Map<String, OutcomeKind> codes = new HashMap<>();
        codes.put("0000", null);

        assertThatThrownBy(() -> new ResponseRuleSet(STATUS_CODES, "/resultCode", codes))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결과 코드를 쓰지 않으면 위치는 null, 표는 빈 표로 맞춘다")
    void normalizesMissingResultCodes() {
        ResponseRuleSet rules = new ResponseRuleSet(STATUS_CODES, " ", Map.of());

        assertThat(rules.resultField()).isNull();
        assertThat(rules.resultCodes()).isEmpty();
    }

    @Test
    @DisplayName("같은 내용이면 같은 규칙으로 본다")
    void equalByContent() {
        assertThat(new ResponseRuleSet(new HashMap<>(STATUS_CODES), null, null))
                .isEqualTo(new ResponseRuleSet(STATUS_CODES, null, null));
    }
}
