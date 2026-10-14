package com.hyuk.flow_control.adapter.out.http;

import java.util.Map;

public class TargetSystemResponseRules {
    private final Map<String, ResponseRules> rules;

    public TargetSystemResponseRules(Map<String, ResponseRules> rules) {
        this.rules = Map.copyOf(rules);
    }

    public ResponseRules get(String targetSystemId) {
        ResponseRules found = rules.get(targetSystemId);

        if (found == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return found;
    }
}
