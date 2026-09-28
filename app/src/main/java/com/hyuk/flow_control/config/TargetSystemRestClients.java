package com.hyuk.flow_control.config;

import org.springframework.web.client.RestClient;

import java.util.Map;

public class TargetSystemRestClients {
    private final Map<String, RestClient> clients;

    public TargetSystemRestClients(Map<String, RestClient> clients) {
        this.clients = Map.copyOf(clients);
    }

    public RestClient get(String targetSystemId) {
        RestClient client = clients.get(targetSystemId);

        if (client == null) {
            throw new IllegalArgumentException("설정에 없는 대상 시스템입니다 : " + targetSystemId);
        }

        return client;
    }
}
