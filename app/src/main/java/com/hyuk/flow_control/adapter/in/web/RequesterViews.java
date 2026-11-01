package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.adapter.kafka.RequestTopics;
import com.hyuk.flow_control.domain.requester.Requester;
import com.hyuk.flow_control.domain.requester.RequesterHistoryEntry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RequesterViews {
    private RequesterViews() {
    }

    public record RequesterView(String requesterId, long version, boolean enabled, List<String> targetSystemIds,
                                Map<String, String> requestTopics, String resultTopic) {
        static RequesterView from(Requester requester) {
            Map<String, String> requestTopics = new LinkedHashMap<>();
            requester.targetSystemIds().forEach(targetSystemId ->
                    requestTopics.put(targetSystemId, RequestTopics.of(targetSystemId, requester.requesterId())));

            return new RequesterView(requester.requesterId(), requester.version(), requester.enabled(),
                    requester.targetSystemIds(), requestTopics, RequestTopics.result(requester.requesterId()));
        }
    }

    public record History(long version, Instant changedAt, String changedBy, String reason, boolean enabled,
                          List<String> targetSystemIds) {
        static History from(RequesterHistoryEntry entry) {
            return new History(
                    entry.requester().version(),
                    entry.changedAt(),
                    entry.note().changedBy(),
                    entry.note().reason(),
                    entry.requester().enabled(),
                    entry.requester().targetSystemIds());
        }
    }
}
