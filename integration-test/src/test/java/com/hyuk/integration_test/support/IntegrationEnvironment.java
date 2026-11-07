package com.hyuk.integration_test.support;

import org.testcontainers.containers.ComposeContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * infra/docker-compose.yml 의 experiment 프로필을 테스트 전체에서 한 번만 띄운다.
 * 포트가 고정되어 있어, 같은 포트를 쓰는 컨테이너가 이미 켜져 있으면 시작하지 않는다.
 */
public final class IntegrationEnvironment {
    private static final String PROFILE = "experiment";
    private static final int APP_MANAGEMENT_PORT = 8081;
    private static final List<Integer> PUBLISHED_PORTS = List.of(8080, APP_MANAGEMENT_PORT, 8090, 8095, 3000, 5432, 6379,
            9001, 9002, 9003, 9090, 9092, 9094, 9096, 9101, 9102, 9103);
    private static final String ADMIN_URL = "http://localhost:8080/admin/policies/";
    private static final Map<String, String> ADMIN_HEADERS = Map.of("Authorization", "Bearer experiment-admin-key");
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration MANUAL_CHECK_LIMIT = Duration.ofMinutes(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static boolean started;
    private static RuntimeException startFailure;
    private static String projectName;

    private IntegrationEnvironment() {
    }

    public static synchronized void start() {
        if (startFailure != null) {
            throw new IllegalStateException("환경을 시작하지 못해 테스트를 돌리지 않습니다", startFailure);
        }
        if (started) {
            return;
        }

        try {
            requirePortsFree();

            ComposeContainer compose = new ComposeContainer(composeFile())
                    .withEnv("COMPOSE_PROFILES", PROFILE)
                    .withBuild(true)
                    .withPull(false)
                    .withRemoveVolumes(true);
            Runtime.getRuntime().addShutdownHook(new Thread(compose::stop));
            compose.start();

            projectName = findProjectName();
            awaitServicesReady();
            pointPoliciesAtContainers();
            started = true;
        } catch (RuntimeException e) {
            startFailure = e;
            throw e;
        }
    }

    public static synchronized boolean isStarted() {
        return started;
    }

    /** 화면을 확인할 동안 JVM 을 붙잡아 둔다. JVM 이 끝나면 Testcontainers 가 컨테이너를 지운다. */
    public static void waitForManualChecks() {
        Path finishFile = Path.of(System.getProperty("integration.finishFile"));
        System.out.println("화면을 다 봤으면 이 파일을 만드세요. " + MANUAL_CHECK_LIMIT.toMinutes()
                + "분이 지나면 자동으로 끝납니다 : " + finishFile);

        long deadline = System.nanoTime() + MANUAL_CHECK_LIMIT.toNanos();
        try {
            Files.deleteIfExists(finishFile);
            while (System.nanoTime() < deadline && !Files.exists(finishFile)) {
                Await.sleep(Duration.ofSeconds(2));
            }
            Files.deleteIfExists(finishFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static boolean keepContainers() {
        return Boolean.getBoolean("integration.keepContainers");
    }

    public static List<JsonNode> composeContainers() {
        String output = compose("ps", "--all", "--format", "json");
        List<JsonNode> containers = new ArrayList<>();
        output.lines().filter(line -> !line.isBlank()).forEach(line -> containers.add(JSON.readTree(line)));
        return containers;
    }

    public static void recreatePrometheus() {
        compose("up", "-d", "--force-recreate", "--no-deps", "prometheus");
    }

    public static String appLogsSince(java.time.Instant since) {
        String containerId = compose("ps", "-q", "app").trim();
        return Docker.run("logs", "--timestamps", "--since", since.toString(), containerId);
    }

    private static String compose(String... args) {
        List<String> command = new ArrayList<>(List.of(
                "compose", "-p", projectName, "-f", composeFile().getAbsolutePath(), "--profile", PROFILE));
        command.addAll(List.of(args));
        return Docker.run(command.toArray(String[]::new));
    }

    private static File composeFile() {
        return new File(System.getProperty("integration.composeFile"));
    }

    private static void requirePortsFree() {
        for (int port : PUBLISHED_PORTS) {
            String existing = Docker.run("ps", "--filter", "publish=" + port, "--format", "{{.Names}}").trim();
            if (!existing.isEmpty()) {
                throw new IllegalStateException("포트 " + port + " 를 쓰는 컨테이너가 있어 시작하지 않습니다. 기존 환경을 끈 뒤 다시 실행하세요 :\n" + existing);
            }
        }
    }

    private static void awaitServicesReady() {
        Await.until("app 상태 UP", STARTUP_TIMEOUT, () -> isUp("http://localhost:8081/actuator/health"));
        MockTarget.all().forEach(target ->
                Await.until(target.id() + " 상태 UP", STARTUP_TIMEOUT, () -> isUp(target.healthUrl())));
        Await.until("record-ingestor 응답", STARTUP_TIMEOUT, Generator::isReachable);
        Await.until("Prometheus 준비", STARTUP_TIMEOUT, Prometheus::isReady);
        Await.until("Grafana 준비", STARTUP_TIMEOUT, () -> {
            Http.get("http://localhost:3000/api/health");
            return true;
        });
    }

    private static boolean isUp(String healthUrl) {
        return "UP".equals(JSON.readTree(Http.get(healthUrl)).path("status").asString());
    }

    /** 시드 정책의 주소가 localhost 라 app 컨테이너에서 닿지 않아 컨테이너 이름으로 바꾼다. */
    private static void pointPoliciesAtContainers() {
        for (MockTarget target : MockTarget.all()) {
            JsonNode current = JSON.readTree(Http.get(ADMIN_URL + target.id(), ADMIN_HEADERS));
            long version = current.path("version").asLong();

            tools.jackson.databind.node.ObjectNode policy = (tools.jackson.databind.node.ObjectNode) current.path("policy");
            ((tools.jackson.databind.node.ObjectNode) policy.path("connection")).put("url", target.internalUrl());

            tools.jackson.databind.node.ObjectNode body = JSON.createObjectNode();
            body.put("expectedVersion", version);
            body.set("policy", policy);
            body.put("reason", "통합 테스트의 mock 주소로 변경");
            Http.put(ADMIN_URL + target.id(), JSON.writeValueAsString(body), ADMIN_HEADERS);

            String versionExpression = "flow_control_policy_version{target=\"" + target.id() + "\"}";
            Await.until(target.id() + " 정책 반영", Duration.ofSeconds(60),
                    () -> Prometheus.value(versionExpression) > version);
        }
    }

    /** 형식 문자열의 큰따옴표가 Windows 에서 사라지므로, inspect 의 JSON 에서 읽는다. */
    private static String findProjectName() {
        String containerId = Docker.run("ps", "-q", "--filter", "publish=" + APP_MANAGEMENT_PORT).trim();
        return JSON.readTree(Docker.run("inspect", containerId))
                .get(0).path("Config").path("Labels").path("com.docker.compose.project").asString();
    }
}
