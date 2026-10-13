package com.hyuk.flow_control.adapter.kafka;

import com.hyuk.flow_control.domain.retry.RetryStage;

public final class RequestTopics {
    public static final String REQUEST_PREFIX = "flow-control.requests.";
    public static final String RETRY_PREFIX = "flow-control.retry-";
    public static final String DEAD_LETTER_PREFIX = "flow-control.dlq.";

    private RequestTopics() {
    }

    public static String of(String targetSystemId) {
        return REQUEST_PREFIX + targetSystemId;
    }

    public static String retry(RetryStage stage, String targetSystemId) {
        return RETRY_PREFIX + stage.label() + "." + targetSystemId;
    }

    public static String deadLetter(String targetSystemId) {
        return DEAD_LETTER_PREFIX + targetSystemId;
    }
}
