package com.hyuk.flow_control.adapter.out.http;

import com.hyuk.flow_control.domain.policy.ResponseRuleSet;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonResponseJudgeTest {
    private static final ResponseRuleSet STATUS_ONLY = new ResponseRuleSet(
            Map.of("2xx", OutcomeKind.SUCCESS, "5xx", OutcomeKind.SERVER_ERROR), null, null);
    private static final ResponseRuleSet WITH_RESULT_CODE = new ResponseRuleSet(
            Map.of("2xx", OutcomeKind.SUCCESS, "5xx", OutcomeKind.SERVER_ERROR),
            "/resultCode",
            Map.of("0000", OutcomeKind.SUCCESS, "E100", OutcomeKind.BUSINESS_REJECTED));

    private final JsonResponseJudge judge = new JsonResponseJudge(JsonMapper.builder().build());

    @Test
    @DisplayName("주어진 규칙의 상태 코드 표로 판단한다")
    void judgesByStatusCode() {
        assertThat(judge.judge(STATUS_ONLY, 200, "{\"resultCode\":\"E100\"}")).isEqualTo(OutcomeKind.SUCCESS);
        assertThat(judge.judge(STATUS_ONLY, 503, null)).isEqualTo(OutcomeKind.SERVER_ERROR);
    }

    @Test
    @DisplayName("주어진 규칙에 결과 코드 위치가 있으면 본문에서 결과 코드를 읽어 판단한다")
    void judgesByResultCode() {
        assertThat(judge.judge(WITH_RESULT_CODE, 200, "{\"resultCode\":\"E100\"}"))
                .isEqualTo(OutcomeKind.BUSINESS_REJECTED);
        assertThat(judge.judge(WITH_RESULT_CODE, 200, "{}")).isEqualTo(OutcomeKind.UNEXPECTED_RESPONSE);
    }
}
