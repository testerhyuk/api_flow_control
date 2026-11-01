package com.hyuk.flow_control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "flow-control.admin")
public record AdminProperties(
        List<ApiKey> apiKeys
) {
    public AdminProperties {
        if (apiKeys == null || apiKeys.isEmpty()) {
            throw new IllegalArgumentException("admin.api-keys 는 하나 이상 있어야 합니다");
        }
        Set<String> keys = new HashSet<>();
        for (ApiKey apiKey : apiKeys) {
            if (!keys.add(apiKey.key())) {
                throw new IllegalArgumentException("admin.api-keys 의 key 는 서로 달라야 합니다");
            }
        }
        apiKeys = List.copyOf(apiKeys);
    }

    public record ApiKey(String name, String key) {
        public ApiKey {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("admin.api-keys 의 name 은 필수입니다");
            }
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("admin.api-keys 의 key 는 필수입니다");
            }
        }
    }
}
