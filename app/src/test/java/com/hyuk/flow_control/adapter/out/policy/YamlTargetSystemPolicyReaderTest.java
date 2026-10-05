package com.hyuk.flow_control.adapter.out.policy;

import com.hyuk.flow_control.config.TargetSystemProperties;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlTargetSystemPolicyReaderTest {
    private static final TargetSystemPolicy POLICY_A =
            new TargetSystemPolicy(100, Duration.ofMillis(100), 0.95, Duration.ofMillis(10));
    private static final TargetSystemPolicy POLICY_B =
            new TargetSystemPolicy(50, Duration.ofMillis(300), 0.9, Duration.ofMillis(20));

    private YamlTargetSystemPolicyReader policyReader;

    @BeforeEach
    void setUp() {
        TargetSystemProperties properties = new TargetSystemProperties(Map.of(
                "target-a", new TargetSystemProperties.TargetSystem(
                        "http://localhost:9001", Duration.ofSeconds(1), Duration.ofSeconds(3),
                        100, Duration.ofMillis(100), 0.95, Duration.ofMillis(10)),
                "target-b", new TargetSystemProperties.TargetSystem(
                        "http://localhost:9002", Duration.ofSeconds(1), Duration.ofSeconds(3),
                        50, Duration.ofMillis(300), 0.9, Duration.ofMillis(20))
        ));

        policyReader = new YamlTargetSystemPolicyReader(properties);
    }

    @Test
    @DisplayName("설정 값 네 개를 정책으로 옮긴다")
    void read() {
        assertThat(policyReader.read("target-a")).isEqualTo(POLICY_A);
        assertThat(policyReader.read("target-b")).isEqualTo(POLICY_B);
    }

    @Test
    @DisplayName("설정에 없는 대상이면 예외가 난다")
    void readRejectsUnknownTarget() {
        assertThatThrownBy(() -> policyReader.read("target-z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("readAll 은 모든 대상의 정책을 돌려준다")
    void readAll() {
        assertThat(policyReader.readAll()).isEqualTo(Map.of(
                "target-a", POLICY_A,
                "target-b", POLICY_B
        ));
    }

    @Test
    @DisplayName("readAll 이 돌려준 Map 은 바꿀 수 없다")
    void readAllReturnsImmutableMap() {
        Map<String, TargetSystemPolicy> policies = policyReader.readAll();

        assertThatThrownBy(() -> policies.put("target-z", POLICY_A))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
