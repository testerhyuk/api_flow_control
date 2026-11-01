package com.hyuk.flow_control.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminPropertiesTest {

    @Test
    @DisplayName("관리 API 키가 하나도 없으면 만들 수 없다")
    void requiresKeys() {
        assertThatThrownBy(() -> new AdminProperties(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminProperties(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이름이나 키가 비었거나 키가 겹치면 만들 수 없다")
    void rejectsInvalidKeys() {
        assertThatThrownBy(() -> new AdminProperties.ApiKey(" ", "key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminProperties.ApiKey("hyuk", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminProperties(List.of(
                new AdminProperties.ApiKey("hyuk", "same"), new AdminProperties.ApiKey("kim", "same"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
