package com.hyuk.flow_control.config;

import com.hyuk.flow_control.domain.request.OutcomeKind;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "flow-control")
public record TargetSystemProperties(
        Map<String, TargetSystem> targetSystems
) {
    public TargetSystemProperties {
        if (targetSystems == null || targetSystems.isEmpty()) {
            throw new IllegalArgumentException("대상 시스템이 설정되지 않았습니다");
        }
    }

    public record TargetSystem(
            String url,
            Duration connectTimeout,
            Duration readTimeout,
            int maxTps,
            Duration baselineRtt,
            double safetyMargin,
            Duration burstWindow,
            Response response
    ) {
        public TargetSystem {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("url 은 필수입니다");
            }
            if (connectTimeout == null || !connectTimeout.isPositive()) {
                throw new IllegalArgumentException("connect-timeout 은 0보다 커야 합니다");
            }
            if (readTimeout == null || !readTimeout.isPositive()) {
                throw new IllegalArgumentException("read-timeout 은 0보다 커야 합니다");
            }
            if (maxTps <= 0) {
                throw new IllegalArgumentException("max-tps는 0보다 커야 합니다");
            }
            if (baselineRtt == null || !baselineRtt.isPositive()) {
                throw new IllegalArgumentException("baseline-rtt는 0보다 커야 합니다");
            }
            if (safetyMargin <= 0 || safetyMargin > 1) {
                throw new IllegalArgumentException("safety-margin 은 0보다 크고 1 이하여야 합니다");
            }
            if (burstWindow == null || !burstWindow.isPositive()) {
                throw new IllegalArgumentException("burst-window 는 0보다 커야 합니다");
            }
            if (response == null) {
                throw new IllegalArgumentException("response 는 필수입니다");
            }
        }
    }

    public record Response(
            Map<String, OutcomeKind> statusCodes,
            String resultField,
            Map<String, OutcomeKind> resultCodes
    ) {
        private static final Pattern STATUS_CODE = Pattern.compile("[1-5](\\d\\d|xx)");

        public Response {
            if (statusCodes == null || statusCodes.isEmpty()) {
                throw new IllegalArgumentException("status-codes 는 필수입니다");
            }
            for (String code : statusCodes.keySet()) {
                if (!STATUS_CODE.matcher(code).matches()) {
                    throw new IllegalArgumentException("status-codes 의 코드 형식이 잘못되었습니다 : " + code);
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

            statusCodes = Map.copyOf(statusCodes);
            resultField = hasResultField ? resultField : null;
            resultCodes = hasResultCodes ? Map.copyOf(resultCodes) : Map.of();
        }
    }
}
