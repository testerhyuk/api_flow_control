package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.RequesterChangeListener;
import com.hyuk.flow_control.application.port.out.RequesterRepository;
import com.hyuk.flow_control.domain.requester.Requester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.hyuk.flow_control.application.port.out.RequesterChangeListeners;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequesterRegistryTest {
    private static final Requester LOAN = new Requester("loan", 1, true, List.of("target-a", "target-b"));
    private static final Requester CARD = new Requester("card", 1, false, List.of("target-a"));

    private RequesterRepository repository;
    private RequesterChangeListener first;
    private RequesterChangeListener second;
    private RequesterChangeListeners listeners;

    @BeforeEach
    void setUp() {
        repository = mock(RequesterRepository.class);
        first = mock(RequesterChangeListener.class);
        second = mock(RequesterChangeListener.class);
        listeners = () -> List.of(first, second);
    }

    @Test
    @DisplayName("외부 서버별로 부를 수 있는 요청자와 켜진 요청자를 알려준다")
    void findsRequestersOfTarget() {
        when(repository.loadAll()).thenReturn(List.of(LOAN, CARD));

        RequesterRegistry registry = new RequesterRegistry(repository, listeners);

        assertThat(registry.requesterIdsOf("target-a")).containsExactly("card", "loan");
        assertThat(registry.enabledRequesterIdsOf("target-a")).containsExactly("loan");
        assertThat(registry.requesterIdsOf("target-c")).isEmpty();
        verifyNoInteractions(first, second);
    }

    @Test
    @DisplayName("새로 읽은 요청자와 버전이 오른 요청자만 반영하고 알린다")
    void appliesNewAndHigherVersions() {
        when(repository.loadAll()).thenReturn(List.of(LOAN));
        RequesterRegistry registry = new RequesterRegistry(repository, listeners);

        Requester loanV2 = LOAN.withEnabled(false).withVersion(2);
        when(repository.loadAll()).thenReturn(List.of(loanV2, CARD));
        registry.refresh();

        assertThat(registry.enabledRequesterIdsOf("target-a")).isEmpty();
        verify(first).onRequesterChanged(LOAN, loanV2);
        verify(first).onRequesterChanged(null, CARD);
        verify(second).onRequesterChanged(LOAN, loanV2);
    }

    @Test
    @DisplayName("버전이 같으면 알리지 않는다")
    void ignoresSameVersion() {
        when(repository.loadAll()).thenReturn(List.of(LOAN));
        RequesterRegistry registry = new RequesterRegistry(repository, listeners);

        registry.refresh();

        verifyNoInteractions(first, second);
    }

    @Test
    @DisplayName("다시 읽지 못하면 마지막으로 읽은 요청자를 계속 쓴다")
    void keepsLastStateWhenRefreshFails() {
        when(repository.loadAll()).thenReturn(List.of(LOAN));
        RequesterRegistry registry = new RequesterRegistry(repository, listeners);

        when(repository.loadAll()).thenThrow(new IllegalStateException("db down"));
        registry.refresh();

        assertThat(registry.requesterIdsOf("target-a")).containsExactly("loan");
    }

    @Test
    @DisplayName("한 listener 가 실패해도 다음 listener 에 알린다")
    void continuesAfterListenerFailure() {
        when(repository.loadAll()).thenReturn(List.of());
        RequesterRegistry registry = new RequesterRegistry(repository, listeners);
        doThrow(new IllegalStateException("kafka down")).when(first).onRequesterChanged(any(), any());

        when(repository.loadAll()).thenReturn(List.of(LOAN));
        registry.refresh();

        verify(second).onRequesterChanged(null, LOAN);
        assertThat(registry.requesters()).containsExactly(LOAN);
    }
}
