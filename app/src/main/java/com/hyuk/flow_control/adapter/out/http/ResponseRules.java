package com.hyuk.flow_control.adapter.out.http;

import com.hyuk.flow_control.domain.policy.ResponseRuleSet;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

public class ResponseRules {
    private final Map<Integer, OutcomeKind> exactCodes;
    private final Map<Integer, OutcomeKind> codeClasses;
    private final JsonPointer resultField;
    private final Map<String, OutcomeKind> resultCodes;

    public ResponseRules(ResponseRuleSet response) {
        Map<Integer, OutcomeKind> exact = new HashMap<>();
        Map<Integer, OutcomeKind> classes = new HashMap<>();

        response.statusCodes().forEach((code, kind) -> {
            if (code.endsWith("xx")) {
                classes.put(Character.getNumericValue(code.charAt(0)), kind);
            } else {
                exact.put(Integer.parseInt(code), kind);
            }
        });

        this.exactCodes = Map.copyOf(exact);
        this.codeClasses = Map.copyOf(classes);
        this.resultField = response.resultField() == null ? null : JsonPointer.compile(response.resultField());
        this.resultCodes = response.resultCodes();
    }

    public boolean readsResultCode() {
        return resultField != null;
    }

    public String readResultCode(String body, JsonMapper jsonMapper) {
        if (!readsResultCode() || body == null || body.isBlank()) {
            return null;
        }

        try {
            JsonNode value = jsonMapper.readTree(body).at(resultField);

            if (value.isMissingNode() || value.isNull() || value.isObject() || value.isArray()) {
                return null;
            }

            return value.asString();
        } catch (JacksonException e) {
            return null;
        }
    }

    public OutcomeKind judge(int statusCode, String resultCode) {
        if (readsResultCode()) {
            if (resultCode != null) {
                return resultCodes.getOrDefault(resultCode, OutcomeKind.UNEXPECTED_RESPONSE);
            }
            if (statusCode / 100 == 2) {
                return OutcomeKind.UNEXPECTED_RESPONSE;
            }
        }

        OutcomeKind exact = exactCodes.get(statusCode);
        if (exact != null) {
            return exact;
        }

        return codeClasses.getOrDefault(statusCode / 100, OutcomeKind.UNEXPECTED_RESPONSE);
    }
}
