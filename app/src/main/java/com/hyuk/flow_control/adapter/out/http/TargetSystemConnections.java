package com.hyuk.flow_control.adapter.out.http;

import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.service.PolicyRegistry;
import com.hyuk.flow_control.domain.policy.ConnectionSettings;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(0)
public class TargetSystemConnections implements PolicyChangeListener {
    private static final Logger log = LoggerFactory.getLogger(TargetSystemConnections.class);

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public TargetSystemConnections(PolicyRegistry policies) {
        policies.policies().forEach(policy -> connections.put(policy.targetSystemId(), Connection.open(policy)));
    }

    public Connection get(String targetSystemId) {
        Connection connection = connections.get(targetSystemId);

        if (connection == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return connection;
    }

    @Override
    public void onPolicyAdded(ManagedPolicy policy) {
        connections.putIfAbsent(policy.targetSystemId(), Connection.open(policy));
    }

    @Override
    public void onPolicyChanged(ManagedPolicy before, ManagedPolicy after) {
        String targetSystemId = after.targetSystemId();
        Connection current = get(targetSystemId);

        if (!before.connection().equals(after.connection())) {
            connections.put(targetSystemId, Connection.open(after));
            closeAfterInFlight(targetSystemId, current);
            return;
        }

        if (!before.response().equals(after.response())) {
            connections.put(targetSystemId, current.withRules(new ResponseRules(after.response())));
        }
    }

    private static void closeAfterInFlight(String targetSystemId, Connection old) {
        Thread.ofVirtual().name("close-http-" + targetSystemId).start(() -> {
            old.httpClient().close();
            log.info("이전 HTTP 클라이언트를 닫았습니다 : {}", targetSystemId);
        });
    }

    public record Connection(RestClient restClient, HttpClient httpClient, ResponseRules rules) {

        static Connection open(ManagedPolicy policy) {
            ConnectionSettings settings = policy.connection();
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(settings.connectTimeout())
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(settings.readTimeout());

            RestClient restClient = RestClient.builder()
                    .baseUrl(settings.url())
                    .requestFactory(requestFactory)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .build();

            return new Connection(restClient, httpClient, new ResponseRules(policy.response()));
        }

        Connection withRules(ResponseRules newRules) {
            return new Connection(restClient, httpClient, newRules);
        }
    }
}
