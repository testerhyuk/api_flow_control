package com.hyuk.flow_control.domain.policy;

import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedPolicyTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"target a", "target/a", "대상"})
    @DisplayName("대상 시스템 이름은 영문, 숫자, '.', '_', '-' 만 쓸 수 있다")
    void rejectsInvalidTargetSystemId(String targetSystemId) {
        assertThatThrownBy(() -> new ManagedPolicy(targetSystemId, 1, true,
                Policies.CONNECTION, Policies.LIMITS, Policies.STATUS_RULES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("대상 시스템 이름은 100자까지 쓸 수 있다")
    void limitsTargetSystemIdLength() {
        assertThat(Policies.policy("a".repeat(100)).targetSystemId()).hasSize(100);
        assertThatThrownBy(() -> Policies.policy("a".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("version 이 1 보다 작으면 만들 수 없다")
    void rejectsVersionBelowOne() {
        assertThatThrownBy(() -> Policies.policy("target-a", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("연결, 한도, 응답 규칙이 없으면 만들 수 없다")
    void requiresParts() {
        assertThatThrownBy(() -> new ManagedPolicy("t", 1, true, null, Policies.LIMITS, Policies.STATUS_RULES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ManagedPolicy("t", 1, true, Policies.CONNECTION, null, Policies.STATUS_RULES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ManagedPolicy("t", 1, true, Policies.CONNECTION, Policies.LIMITS, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("버전과 사용 여부만 바꾼 정책을 만든다")
    void copiesWithVersionAndEnabled() {
        ManagedPolicy policy = Policies.policy("target-a");

        ManagedPolicy changed = policy.withEnabled(false).withVersion(5);

        assertThat(changed.version()).isEqualTo(5);
        assertThat(changed.enabled()).isFalse();
        assertThat(changed.connection()).isEqualTo(policy.connection());
        assertThat(changed.limits()).isEqualTo(policy.limits());
        assertThat(changed.response()).isEqualTo(policy.response());
    }
}
