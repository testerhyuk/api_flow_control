package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.config.AdminProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

@Component
public class AdminApiKeyFilter extends OncePerRequestFilter {
    public static final String OPERATOR = "flow-control.admin.operator";

    private static final String ADMIN_PATH = "/admin/";
    private static final String BEARER = "Bearer ";
    private static final String UNAUTHORIZED_BODY =
            "{\"status\":401,\"detail\":\"관리 API 키가 없거나 맞지 않습니다\"}";

    private final List<AdminProperties.ApiKey> apiKeys;

    public AdminApiKeyFilter(AdminProperties properties) {
        this.apiKeys = properties.apiKeys();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !pathOf(request).startsWith(ADMIN_PATH);
    }

    private static String pathOf(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? request.getServletPath() : request.getServletPath() + pathInfo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> operator = operatorOf(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (operator.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }

        request.setAttribute(OPERATOR, operator.get());
        chain.doFilter(request, response);
    }

    private Optional<String> operatorOf(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return Optional.empty();
        }

        byte[] given = authorization.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8);
        String matched = null;
        for (AdminProperties.ApiKey apiKey : apiKeys) {
            if (MessageDigest.isEqual(given, apiKey.key().getBytes(StandardCharsets.UTF_8))) {
                matched = apiKey.name();
            }
        }
        return Optional.ofNullable(matched);
    }
}
