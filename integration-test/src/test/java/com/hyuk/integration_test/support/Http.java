package com.hyuk.integration_test.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class Http {
    private static final Duration TIMEOUT = Duration.ofSeconds(180);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Http() {
    }

    public static String get(String url) {
        return get(url, Map.of());
    }

    public static String get(String url, Map<String, String> headers) {
        return send(request(url, headers).GET());
    }

    public static String post(String url, String json) {
        return post(url, json, Map.of());
    }

    public static String post(String url, String json, Map<String, String> headers) {
        return send(request(url, headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    public static String put(String url, String json, Map<String, String> headers) {
        return send(request(url, headers)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    public static String delete(String url) {
        return send(request(url, Map.of()).DELETE());
    }

    private static HttpRequest.Builder request(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT);
        headers.forEach(builder::header);
        return builder;
    }

    private static String send(HttpRequest.Builder builder) {
        HttpRequest request = builder.build();
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(request.method() + " " + request.uri() + " 응답 "
                        + response.statusCode() + " : " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException(request.method() + " " + request.uri() + " 호출 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("호출 중 중단되었습니다", e);
        }
    }
}
