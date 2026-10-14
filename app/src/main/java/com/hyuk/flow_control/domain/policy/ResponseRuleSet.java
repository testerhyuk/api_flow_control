package com.hyuk.flow_control.domain.policy;

import com.hyuk.flow_control.domain.request.OutcomeKind;

import java.util.Map;
import java.util.regex.Pattern;

public record ResponseRuleSet(
        Map<String, OutcomeKind> statusCodes,
        String resultField,
        Map<String, OutcomeKind> resultCodes
) {
    private static final Pattern STATUS_CODE = Pattern.compile("[1-5](\\d\\d|xx)");

    public ResponseRuleSet {
        if (statusCodes == null || statusCodes.isEmpty()) {
            throw new IllegalArgumentException("status-codes 는 필수입니다");
        }
        for (Map.Entry<String, OutcomeKind> entry : statusCodes.entrySet()) {
            if (entry.getKey() == null || !STATUS_CODE.matcher(entry.getKey()).matches()) {
                throw new IllegalArgumentException("status-codes 의 코드 형식이 잘못되었습니다 : " + entry.getKey());
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("status-codes 의 결과가 비어 있습니다 : " + entry.getKey());
            }
        }

        boolean hasResultField = resultField != null && !resultField.isBlank();
        boolean hasResultCodes = resultCodes != null && !resultCodes.isEmpty();

        if (hasResultField != hasResultCodes) {
            throw new IllegalArgumentException("result-field 와 result-codes 는 함께 적어야 합니다");
        }
        if (hasResultField && !resultField.startsWith("/")) {
            throw new IllegalArgumentException("result-field 는 / 로 시작해야 합니다 : " + resultField);
        }
        if (hasResultCodes) {
            for (Map.Entry<String, OutcomeKind> entry : resultCodes.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()) {
                    throw new IllegalArgumentException("result-codes 의 코드가 비어 있습니다");
                }
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("result-codes 의 결과가 비어 있습니다 : " + entry.getKey());
                }
            }
        }

        statusCodes = Map.copyOf(statusCodes);
        resultField = hasResultField ? resultField : null;
        resultCodes = hasResultCodes ? Map.copyOf(resultCodes) : Map.of();
    }
}
