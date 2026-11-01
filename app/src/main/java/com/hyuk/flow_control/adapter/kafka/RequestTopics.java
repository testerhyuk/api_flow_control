package com.hyuk.flow_control.adapter.kafka;

import com.hyuk.flow_control.domain.retry.RetryStage;

public final class RequestTopics {
    public static final String REQUEST_PREFIX = "flow-control.requests.";
    public static final String RETRY_PREFIX = "flow-control.retry-";
    public static final String DEAD_LETTER_PREFIX = "flow-control.dlq.";
    public static final String EXPIRED_PREFIX = "flow-control.expired.";
    public static final String RESULT_PREFIX = "flow-control.results.";

    private RequestTopics() {
    }

    public static String of(String targetSystemId, String requesterId) {
        return REQUEST_PREFIX + targetSystemId + "." + requesterId;
    }

    public static String requesterOf(String requestTopic, String targetSystemId) {
        String prefix = REQUEST_PREFIX + targetSystemId + ".";
        if (!requestTopic.startsWith(prefix)) {
            throw new IllegalArgumentException("이 외부 서버의 요청 토픽이 아닙니다 : " + requestTopic);
        }
        return requestTopic.substring(prefix.length());
    }

    public static String result(String requesterId) {
        return RESULT_PREFIX + requesterId;
    }

    public static String retry(RetryStage stage, String targetSystemId) {
        return RETRY_PREFIX + stage.label() + "." + targetSystemId;
    }

    public static String deadLetter(String targetSystemId) {
        return DEAD_LETTER_PREFIX + targetSystemId;
    }

    public static String expired(String targetSystemId) {
        return EXPIRED_PREFIX + targetSystemId;
    }
}
