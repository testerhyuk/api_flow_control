package com.hyuk.flow_control.support;

import com.hyuk.flow_control.adapter.in.web.AdminApiKeyFilter;
import com.hyuk.flow_control.config.AdminProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

public final class AdminKeys {
    public static final String OPERATOR = "hyuk";
    public static final String KEY = "test-admin-key";

    private AdminKeys() {
    }

    public static AdminApiKeyFilter filter() {
        return new AdminApiKeyFilter(new AdminProperties(List.of(new AdminProperties.ApiKey(OPERATOR, KEY))));
    }

    public static MockHttpServletRequestBuilder authorized() {
        return get("/").header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY);
    }
}
