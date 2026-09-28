package com.hyuk.flow_control.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class RestClientConfig {
    @Bean
    public TargetSystemRestClients targetSystemRestClients(TargetSystemProperties properties) {
        Map<String, RestClient> clients = new HashMap<>();

        properties.targetSystems().forEach((systemId, target) -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(target.connectTimeout())
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(target.readTimeout());

            RestClient client = RestClient.builder()
                    .baseUrl(target.url())
                    .requestFactory(requestFactory)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .build();

            clients.put(systemId, client);
        });

        return new TargetSystemRestClients(clients);
    }
}
