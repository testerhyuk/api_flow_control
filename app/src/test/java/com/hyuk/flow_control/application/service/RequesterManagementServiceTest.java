package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.out.PolicyRepository;
import com.hyuk.flow_control.application.port.out.RequesterRepository;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.requester.Requester;
import com.hyuk.flow_control.domain.requester.RequesterNotFoundException;
import com.hyuk.flow_control.domain.requester.RequesterVersionConflictException;
import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequesterManagementServiceTest {
    private static final ChangeNote NOTE = new ChangeNote("hyuk", "새 요청자");
    private static final Requester CURRENT = new Requester("loan", 3, true, List.of("target-a", "target-b"));

    private RequesterRepository repository;
    private PolicyRepository policies;
    private RequesterRegistry registry;
    private RequesterManagementService service;

    @BeforeEach
    void setUp() {
        repository = mock(RequesterRepository.class);
        policies = mock(PolicyRepository.class);
        registry = mock(RequesterRegistry.class);
        service = new RequesterManagementService(repository, policies, registry);

        when(policies.load(anyString())).thenReturn(Optional.empty());
        for (String target : List.of("target-a", "target-b", "target-c")) {
            when(policies.load(target)).thenReturn(Optional.of(new LoadedPolicy.Valid(Policies.policy(target))));
        }
        when(repository.load("loan")).thenReturn(Optional.of(CURRENT));
    }

    @Test
    @DisplayName("등록하면 버전 1, 사용 중으로 저장하고 바로 반영한다")
    void registersVersionOne() {
        Requester registered = service.register("card", List.of("target-a"), NOTE);

        assertThat(registered).isEqualTo(new Requester("card", 1, true, List.of("target-a")));
        verify(repository).insert(eq(registered), eq(NOTE), any());
        verify(registry).refresh();
    }

    @Test
    @DisplayName("정책이 없는 외부 서버로는 등록할 수 없다")
    void rejectsTargetWithoutPolicy() {
        assertThatThrownBy(() -> service.register("card", List.of("target-z"), NOTE))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).insert(any(), any(), any());
    }

    @Test
    @DisplayName("없는 요청자를 조회하면 RequesterNotFoundException 이다")
    void getMissing() {
        when(repository.load("card")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("card")).isInstanceOf(RequesterNotFoundException.class);
    }

    @Test
    @DisplayName("끄면 보낸 버전 다음 버전으로, 보낸 버전일 때만 저장하고 바로 반영한다")
    void disablesWithNextVersion() {
        Requester changed = service.changeEnabled("loan", false, 3, NOTE);

        assertThat(changed.enabled()).isFalse();
        assertThat(changed.version()).isEqualTo(4);
        verify(repository).update(eq(changed), eq(3L), eq(NOTE), any());
        verify(registry).refresh();
    }

    @Test
    @DisplayName("외부 서버를 더하고 뺀다")
    void addsAndRemovesTarget() {
        assertThat(service.addTarget("loan", "target-c", 3, NOTE).targetSystemIds())
                .containsExactly("target-a", "target-b", "target-c");
        assertThat(service.removeTarget("loan", "target-a", 3, NOTE).targetSystemIds())
                .containsExactly("target-b");
    }

    @Test
    @DisplayName("정책이 없는 외부 서버는 더할 수 없다")
    void rejectsAddingTargetWithoutPolicy() {
        assertThatThrownBy(() -> service.addTarget("loan", "target-z", 3, NOTE))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).update(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("버전이 어긋나면 충돌 예외가 나고 반영하지 않는다")
    void conflictDoesNotRefresh() {
        doThrow(new RequesterVersionConflictException("loan", 2))
                .when(repository).update(any(), eq(2L), any(), any());

        assertThatThrownBy(() -> service.changeEnabled("loan", false, 2, NOTE))
                .isInstanceOf(RequesterVersionConflictException.class);
        verify(registry, never()).refresh();
    }

    @Test
    @DisplayName("없는 요청자의 이력은 RequesterNotFoundException 이다")
    void historyOfMissing() {
        when(repository.load("card")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.history("card")).isInstanceOf(RequesterNotFoundException.class);
    }
}
