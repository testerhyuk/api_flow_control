package com.hyuk.flow_control.adapter.in.kafka;

public final class RequestTopics {
    public static final String PREFIX = "flow-control.requests.";

    private RequestTopics() {
    }

    public static String of(String targetSystemId) {
        return PREFIX + targetSystemId;
    }

    public static String targetOf(String topic) {
        if (topic == null || !topic.startsWith(PREFIX) || topic.length() == PREFIX.length()) {
            throw new IllegalArgumentException("요청 토픽 이름이 아닙니다 : " + topic);
        }
        return topic.substring(PREFIX.length());
    }
}
