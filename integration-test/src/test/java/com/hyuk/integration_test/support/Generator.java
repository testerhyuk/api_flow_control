package com.hyuk.integration_test.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.stream.Collectors;

public final class Generator {
    private static final String BASE_URL = "http://localhost:8095/generator";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Generator() {
    }

    public record TargetStatus(double rate, long sent, long failed) {
    }

    public static void start(Map<String, Integer> rates, long durationSeconds) {
        String rateJson = rates.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
                .collect(Collectors.joining(","));
        Http.post(BASE_URL + "/start",
                "{\"seed\":1,\"rates\":{" + rateJson + "},\"durationSeconds\":" + durationSeconds + "}");
    }

    public static void stop() {
        Http.post(BASE_URL + "/stop", "");
    }

    public static boolean isStopped() {
        return "STOPPED".equals(status().path("state").asString());
    }

    public static boolean isReachable() {
        status();
        return true;
    }

    public static TargetStatus targetStatus(String target) {
        JsonNode node = status().path("targets").path(target);
        return new TargetStatus(node.path("rate").asDouble(), node.path("sent").asLong(), node.path("failed").asLong());
    }

    private static JsonNode status() {
        return JSON.readTree(Http.get(BASE_URL));
    }
}
