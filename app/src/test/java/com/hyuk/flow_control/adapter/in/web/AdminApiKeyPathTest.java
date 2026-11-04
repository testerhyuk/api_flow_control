package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.application.port.in.PolicyManagementUseCase;
import com.hyuk.flow_control.support.AdminKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = AdminApiKeyPathTest.Web.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminApiKeyPathTest {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private PolicyManagementUseCase management;

    @BeforeEach
    void setUp() {
        clearInvocations(management);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/admin/policies",
            "/admin;x=1/policies",
            "/%61dmin/policies",
            "//admin/policies",
            "/admin/./policies"
    })
    @DisplayName("주소 표기를 바꿔도 키가 없으면 관리 API 는 401 이고 컨트롤러까지 가지 않는다")
    void rejectsAnyFormOfAdminPath(String path) throws Exception {
        HttpResponse<String> response = send(path, null);

        assertThat(response.statusCode()).as(path).isEqualTo(401);
        verify(management, never()).list();
    }

    @Test
    @DisplayName("맞는 키면 실제 서버에서도 통과한다")
    void acceptsKey() throws Exception {
        HttpResponse<String> response = send("/admin/policies", "Bearer " + AdminKeys.KEY);

        assertThat(response.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> send(String path, String authorization) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration
    @EnableWebMvc
    static class Web {
        @Bean
        TomcatServletWebServerFactory tomcat() {
            return new TomcatServletWebServerFactory(0);
        }

        @Bean
        DispatcherServlet dispatcherServlet() {
            return new DispatcherServlet();
        }

        @Bean
        PolicyManagementUseCase management() {
            return mock(PolicyManagementUseCase.class);
        }

        @Bean
        PolicyAdminController policyAdminController(PolicyManagementUseCase management) {
            return new PolicyAdminController(management);
        }

        @Bean
        AdminApiKeyFilter adminApiKeyFilter() {
            return AdminKeys.filter();
        }
    }
}
