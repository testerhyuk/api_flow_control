package com.hyuk.integration_test.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class Prometheus {
    private static final String BASE_URL = "http://localhost:9090";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Prometheus() {
    }

    public record Sample(JsonNode labels, double value) {
        public String label(String name) {
            return labels.path(name).asString("");
        }
    }

    public static List<Sample> query(String expression) {
        return parse(Http.get(BASE_URL + "/api/v1/query?query=" + encode(expression)));
    }

    public static List<Sample> queryAt(String expression, Instant time) {
        String seconds = String.format(java.util.Locale.ROOT, "%.3f", time.toEpochMilli() / 1000.0);
        return parse(Http.get(BASE_URL + "/api/v1/query?query=" + encode(expression) + "&time=" + seconds));
    }

    /** 값이 없으면(아직 한 번도 일어나지 않은 횟수 지표) 0 으로 본다. */
    public static double value(String expression) {
        return firstOrZero(query(expression));
    }

    public static double valueAt(String expression, Instant time) {
        return firstOrZero(queryAt(expression, time));
    }

    /** 상태별 값 중 1 인 상태 이름을 돌려준다. */
    public static String stateOf(String target) {
        return currentState(query(stateExpression(target)));
    }

    public static String stateAt(String target, Instant time) {
        return currentState(queryAt(stateExpression(target), time));
    }

    public static boolean isReady() {
        Http.get(BASE_URL + "/-/ready");
        return true;
    }

    public static JsonNode targets() {
        return JSON.readTree(Http.get(BASE_URL + "/api/v1/targets")).path("data").path("activeTargets");
    }

    public static List<String> alertRuleNames() {
        JsonNode groups = JSON.readTree(Http.get(BASE_URL + "/api/v1/rules?type=alert")).path("data").path("groups");
        List<String> names = new ArrayList<>();
        for (JsonNode group : groups) {
            for (JsonNode rule : group.path("rules")) {
                names.add(rule.path("name").asString());
            }
        }
        return names;
    }

    private static String stateExpression(String target) {
        return "flow_control_health_state{target=\"" + target + "\"}";
    }

    private static String currentState(List<Sample> samples) {
        return samples.stream()
                .filter(sample -> sample.value() == 1.0)
                .map(sample -> sample.label("state"))
                .findFirst()
                .orElse("UNKNOWN");
    }

    private static double firstOrZero(List<Sample> samples) {
        return samples.isEmpty() ? 0.0 : samples.getFirst().value();
    }

    private static List<Sample> parse(String body) {
        List<Sample> samples = new ArrayList<>();
        for (JsonNode result : JSON.readTree(body).path("data").path("result")) {
            samples.add(new Sample(result.path("metric"), Double.parseDouble(result.path("value").get(1).asString())));
        }
        return samples;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
