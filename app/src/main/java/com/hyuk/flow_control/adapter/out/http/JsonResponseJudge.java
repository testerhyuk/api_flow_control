package com.hyuk.flow_control.adapter.out.http;

import com.hyuk.flow_control.application.port.out.ResponseJudge;
import com.hyuk.flow_control.domain.policy.ResponseRuleSet;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JsonResponseJudge implements ResponseJudge {
    private final JsonMapper jsonMapper;

    public JsonResponseJudge(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public OutcomeKind judge(ResponseRuleSet ruleSet, int statusCode, String body) {
        ResponseRules rules = new ResponseRules(ruleSet);
        return rules.judge(statusCode, rules.readResultCode(body, jsonMapper));
    }
}
