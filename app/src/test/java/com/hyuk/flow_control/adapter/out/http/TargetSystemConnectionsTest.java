package com.hyuk.flow_control.adapter.out.http;

import com.hyuk.flow_control.application.service.PolicyRegistry;
import com.hyuk.flow_control.domain.policy.ConnectionSettings;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.ResponseRuleSet;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TargetSystemConnectionsTest {
    private static final String TARGET = "target-a";

    private ManagedPolicy policy;
    private TargetSystemConnections connections;

    @BeforeEach
    void setUp() {
        policy = Policies.policy(TARGET);
        PolicyRegistry policies = mock(PolicyRegistry.class);
        when(policies.policies()).thenReturn(List.of(policy));
        connections = new TargetSystemConnections(policies);
    }

    @Test
    @DisplayName("처음 읽은 정책마다 연결을 만든다")
    void opensConnectionPerPolicy() {
        assertThat(connections.get(TARGET).restClient()).isNotNull();
        assertThat(connections.get(TARGET).rules().judge(200, null)).isEqualTo(OutcomeKind.SUCCESS);
    }

    @Test
    @DisplayName("정책에 없는 대상은 예외가 난다")
    void rejectsUnknownTarget() {
        assertThatThrownBy(() -> connections.get("target-z")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("추가된 대상은 연결을 새로 만든다")
    void opensConnectionForAddedPolicy() {
        connections.onPolicyAdded(Policies.policy("target-d"));

        assertThat(connections.get("target-d").restClient()).isNotNull();
    }

    @Test
    @DisplayName("url 이나 timeout 이 바뀌면 HTTP 클라이언트를 새로 만든다")
    void replacesClientWhenConnectionChanges() {
        TargetSystemConnections.Connection before = connections.get(TARGET);
        ManagedPolicy changed = Policies.withConnection(policy.withVersion(2),
                new ConnectionSettings("http://localhost:9009", Duration.ofSeconds(1), Duration.ofSeconds(5)));

        connections.onPolicyChanged(policy, changed);

        TargetSystemConnections.Connection after = connections.get(TARGET);
        assertThat(after.httpClient()).isNotSameAs(before.httpClient());
        assertThat(after.restClient()).isNotSameAs(before.restClient());
    }

    @Test
    @DisplayName("응답 판단 규칙만 바뀌면 HTTP 클라이언트는 그대로 두고 규칙만 바꾼다")
    void swapsOnlyRulesWhenResponseChanges() {
        TargetSystemConnections.Connection before = connections.get(TARGET);
        ManagedPolicy changed = Policies.withResponse(policy.withVersion(2),
                new ResponseRuleSet(Map.of("2xx", OutcomeKind.BUSINESS_REJECTED), null, null));

        connections.onPolicyChanged(policy, changed);

        TargetSystemConnections.Connection after = connections.get(TARGET);
        assertThat(after.httpClient()).isSameAs(before.httpClient());
        assertThat(after.rules().judge(200, null)).isEqualTo(OutcomeKind.BUSINESS_REJECTED);
    }

    @Test
    @DisplayName("한도만 바뀌면 연결을 건드리지 않는다")
    void keepsConnectionWhenOnlyLimitsChange() {
        TargetSystemConnections.Connection before = connections.get(TARGET);

        connections.onPolicyChanged(policy, policy.withVersion(2));

        assertThat(connections.get(TARGET)).isSameAs(before);
    }
}
