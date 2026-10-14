package com.hyuk.flow_control.application.port.out;

import com.hyuk.flow_control.domain.policy.ResponseRuleSet;
import com.hyuk.flow_control.domain.request.OutcomeKind;

public interface ResponseJudge {
    OutcomeKind judge(ResponseRuleSet rules, int statusCode, String body);
}
