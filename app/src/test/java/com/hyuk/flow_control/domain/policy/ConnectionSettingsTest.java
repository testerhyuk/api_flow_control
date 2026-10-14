package com.hyuk.flow_control.domain.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionSettingsTest {
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    @DisplayName("url 이 없거나 비어 있으면 만들 수 없다")
    void rejectsBlankUrl(String url) {
        assertThatThrownBy(() -> new ConnectionSettings(url, ONE_SECOND, ONE_SECOND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("connect-timeout 이 없거나 0 이면 만들 수 없다")
    void rejectsInvalidConnectTimeout() {
        assertThatThrownBy(() -> new ConnectionSettings("http://a", null, ONE_SECOND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionSettings("http://a", Duration.ZERO, ONE_SECOND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("read-timeout 이 없거나 0 이면 만들 수 없다")
    void rejectsInvalidReadTimeout() {
        assertThatThrownBy(() -> new ConnectionSettings("http://a", ONE_SECOND, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionSettings("http://a", ONE_SECOND, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
