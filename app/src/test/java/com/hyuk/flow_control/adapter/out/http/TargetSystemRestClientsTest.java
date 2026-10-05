package com.hyuk.flow_control.adapter.out.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetSystemRestClientsTest {
    @Test
    @DisplayName("등록한 대상의 RestClient 를 돌려준다")
    void get() {
        RestClient clientA = RestClient.create();
        RestClient clientB = RestClient.create();
        TargetSystemRestClients restClients = new TargetSystemRestClients(Map.of(
                "target-a", clientA,
                "target-b", clientB
        ));

        assertThat(restClients.get("target-a")).isSameAs(clientA);
        assertThat(restClients.get("target-b")).isSameAs(clientB);
    }

    @Test
    @DisplayName("설정에 없는 대상이면 예외가 난다")
    void rejectsUnknownTarget() {
        TargetSystemRestClients restClients = new TargetSystemRestClients(Map.of(
                "target-a", RestClient.create()
        ));

        assertThatThrownBy(() -> restClients.get("target-z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("만든 뒤에 원본 Map 을 바꿔도 영향을 받지 않는다")
    void copiesSourceMap() {
        RestClient clientA = RestClient.create();
        Map<String, RestClient> source = new HashMap<>();
        source.put("target-a", clientA);
        TargetSystemRestClients restClients = new TargetSystemRestClients(source);

        source.clear();

        assertThat(restClients.get("target-a")).isSameAs(clientA);
    }
}
