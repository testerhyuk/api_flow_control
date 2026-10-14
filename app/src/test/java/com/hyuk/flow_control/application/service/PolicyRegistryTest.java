package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.FlowControlMetrics;
import com.hyuk.flow_control.application.port.out.PolicyChangeListener;
import com.hyuk.flow_control.application.port.out.PolicyRepository;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PolicyRegistryTest {
    private static final ManagedPolicy A_V1 = Policies.policy("target-a", 1);
    private static final ManagedPolicy B_V1 = Policies.policy("target-b", 1);

    private PolicyRepository repository;
    private PolicyChangeListener first;
    private PolicyChangeListener second;
    private FlowControlMetrics metrics;
    private ObjectProvider<PolicyChangeListener> listeners;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(PolicyRepository.class);
        first = mock(PolicyChangeListener.class);
        second = mock(PolicyChangeListener.class);
        metrics = mock(FlowControlMetrics.class);
        listeners = mock(ObjectProvider.class);
        when(listeners.orderedStream()).thenAnswer(invocation -> Stream.of(first, second));
    }

    @Test
    @DisplayName("처음에 DB 의 올바른 정책을 모두 읽는다")
    void loadsValidPolicies() {
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Valid(A_V1), new LoadedPolicy.Valid(B_V1)));

        PolicyRegistry registry = new PolicyRegistry(repository, listeners, metrics);

        assertThat(registry.policyOf("target-a")).isEqualTo(A_V1);
        assertThat(registry.readAll()).containsOnlyKeys("target-a", "target-b");
        assertThat(registry.read("target-b")).isEqualTo(B_V1.limits());
    }

    @Test
    @DisplayName("처음에 잘못된 정책은 건너뛰고 지표에 남긴다")
    void skipsInvalidPolicyAtStart() {
        when(repository.loadAll()).thenReturn(List.of(
                new LoadedPolicy.Valid(A_V1), new LoadedPolicy.Invalid("target-b", 1, "max-tps")));

        PolicyRegistry registry = new PolicyRegistry(repository, listeners, metrics);

        assertThat(registry.readAll()).containsOnlyKeys("target-a");
        verify(metrics).recordInvalidPolicy("target-b");
    }

    @Test
    @DisplayName("처음에 DB 를 읽지 못하면 예외가 나서 앱이 뜨지 않는다")
    void failsWhenDatabaseIsUnavailableAtStart() {
        when(repository.loadAll()).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> new PolicyRegistry(repository, listeners, metrics))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("정책에 없는 대상은 예외가 난다")
    void rejectsUnknownTarget() {
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Valid(A_V1)));

        PolicyRegistry registry = new PolicyRegistry(repository, listeners, metrics);

        assertThatThrownBy(() -> registry.read("target-z")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("다시 읽었을 때 버전이 올라간 정책은 바꾸고 순서대로 알린다")
    void appliesHigherVersion() {
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Valid(A_V1)));
        PolicyRegistry registry = new PolicyRegistry(repository, listeners, metrics);
        ManagedPolicy v2 = A_V1.withVersion(2).withEnabled(false);
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Valid(v2)));

        registry.refresh();

        assertThat(registry.policyOf("target-a")).isEqualTo(v2);
        InOrder order = inOrder(first, second);
        order.verify(first).onPolicyChanged(A_V1, v2);
        order.verify(second).onPolicyChanged(A_V1, v2);
    }

    @Test
    @DisplayName("버전이 같거나 낮으면 바꾸지 않는다")
    void ignoresSameOrLowerVersion() {
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Valid(A_V1.withVersion(3))));
        PolicyRegistry registry = new PolicyRegistry(repository, listeners, metrics);
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Valid(A_V1.withVersion(2))));

        registry.refresh();

        assertThat(registry.policyOf("target-a").version()).isEqualTo(3);
        verifyNoInteractions(first, second);
    }

    @Test
    @DisplayName("새로 생긴 대상은 추가하고 알린다")
    void addsNewTarget() {
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Valid(A_V1)));
        PolicyRegistry registry = new PolicyRegistry(repository, listeners, metrics);
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Valid(A_V1), new LoadedPolicy.Valid(B_V1)));

        registry.refresh();

        assertThat(registry.policyOf("target-b")).isEqualTo(B_V1);
        verify(first).onPolicyAdded(B_V1);
        verify(second).onPolicyAdded(B_V1);
    }

    @Test
    @DisplayName("한 곳에서 반영에 실패해도 나머지에는 알린다")
    void continuesWhenListenerFails() {
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Valid(A_V1)));
        PolicyRegistry registry = new PolicyRegistry(repository, listeners, metrics);
        ManagedPolicy v2 = A_V1.withVersion(2);
        doThrow(new IllegalStateException("boom")).when(first).onPolicyChanged(A_V1, v2);
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Valid(v2)));

        registry.refresh();

        verify(second).onPolicyChanged(A_V1, v2);
    }

    @Test
    @DisplayName("다시 읽을 때 DB 에 문제가 있으면 마지막 정책을 계속 쓰고 지표에 남긴다")
    void keepsLastPoliciesWhenDatabaseFails() {
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Valid(A_V1)));
        PolicyRegistry registry = new PolicyRegistry(repository, listeners, metrics);
        when(repository.loadAll()).thenThrow(new IllegalStateException("db down"));

        registry.refresh();

        assertThat(registry.policyOf("target-a")).isEqualTo(A_V1);
        verify(metrics).recordPolicyRefreshFailure();
    }

    @Test
    @DisplayName("다시 읽은 정책이 잘못됐으면 마지막 정책을 계속 쓰고, 같은 버전은 한 번만 지표에 남긴다")
    void keepsLastPolicyWhenInvalid() {
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Valid(A_V1)));
        PolicyRegistry registry = new PolicyRegistry(repository, listeners, metrics);
        when(repository.loadAll()).thenReturn(List.of(new LoadedPolicy.Invalid("target-a", 2, "safety-margin")));

        registry.refresh();
        registry.refresh();

        assertThat(registry.policyOf("target-a")).isEqualTo(A_V1);
        verify(metrics, times(1)).recordInvalidPolicy("target-a");
        verify(first, never()).onPolicyChanged(any(), any());
    }
}
