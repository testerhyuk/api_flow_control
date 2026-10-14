package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.PolicyContent;
import com.hyuk.flow_control.application.port.out.PolicyRepository;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.PolicyNotFoundException;
import com.hyuk.flow_control.domain.policy.PolicyVersionConflictException;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.support.Policies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyManagementServiceTest {
    private static final String TARGET = "target-a";
    private static final ChangeNote NOTE = new ChangeNote("hyuk", "한도 조정");
    private static final ManagedPolicy CURRENT = Policies.policy(TARGET, 3);
    private static final PolicyContent CONTENT = new PolicyContent(
            true, Policies.CONNECTION,
            new TargetSystemPolicy(80, Duration.ofMillis(150), 0.95, Duration.ofMillis(10)),
            Policies.STATUS_RULES);

    private PolicyRepository repository;
    private PolicyManagementService service;

    @BeforeEach
    void setUp() {
        repository = mock(PolicyRepository.class);
        service = new PolicyManagementService(repository);
    }

    @Test
    @DisplayName("추가하면 버전 1로 저장한다")
    void createsVersionOne() {
        ManagedPolicy created = service.create("target-d", CONTENT, NOTE);

        assertThat(created.version()).isEqualTo(1);
        verify(repository).insert(eq(created), eq(NOTE), any());
    }

    @Test
    @DisplayName("없는 정책을 조회하면 PolicyNotFoundException 이다")
    void getMissing() {
        when(repository.load("target-z")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("target-z")).isInstanceOf(PolicyNotFoundException.class);
    }

    @Test
    @DisplayName("수정하면 보낸 버전 다음 버전으로, 보낸 버전일 때만 저장한다")
    void updatesWithNextVersion() {
        when(repository.load(TARGET)).thenReturn(Optional.of(new LoadedPolicy.Valid(CURRENT)));

        ManagedPolicy updated = service.update(TARGET, CONTENT, 3, NOTE);

        assertThat(updated.version()).isEqualTo(4);
        assertThat(updated.limits().maxTps()).isEqualTo(80);
        verify(repository).update(eq(updated), eq(3L), eq(NOTE), any());
    }

    @Test
    @DisplayName("없는 정책은 수정할 수 없다")
    void updateMissing() {
        when(repository.load(TARGET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(TARGET, CONTENT, 3, NOTE)).isInstanceOf(PolicyNotFoundException.class);
        verify(repository, never()).update(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("그사이 바뀐 정책이면 버전 충돌 예외가 그대로 나간다")
    void propagatesVersionConflict() {
        when(repository.load(TARGET)).thenReturn(Optional.of(new LoadedPolicy.Valid(CURRENT)));
        doThrow(new PolicyVersionConflictException(TARGET, 2)).when(repository).update(any(), eq(2L), any(), any());

        assertThatThrownBy(() -> service.update(TARGET, CONTENT, 2, NOTE))
                .isInstanceOf(PolicyVersionConflictException.class);
    }

    @Test
    @DisplayName("켜고 끄면 나머지 값은 그대로 두고 사용 여부만 바꾼다")
    void changesEnabledOnly() {
        when(repository.load(TARGET)).thenReturn(Optional.of(new LoadedPolicy.Valid(CURRENT)));

        ManagedPolicy changed = service.changeEnabled(TARGET, false, 3, NOTE);

        assertThat(changed).isEqualTo(CURRENT.withEnabled(false).withVersion(4));
    }

    @Test
    @DisplayName("저장된 정책이 잘못돼 있으면 켜고 끌 수 없다")
    void cannotToggleInvalidPolicy() {
        when(repository.load(TARGET)).thenReturn(Optional.of(new LoadedPolicy.Invalid(TARGET, 3, "bad")));

        assertThatThrownBy(() -> service.changeEnabled(TARGET, false, 3, NOTE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("되돌리면 옛 버전의 내용을 새 버전으로 저장한다")
    void rollsBackAsNewVersion() {
        ManagedPolicy past = Policies.policy(TARGET, 1);
        when(repository.findVersion(TARGET, 1)).thenReturn(Optional.of(past));

        ManagedPolicy rolledBack = service.rollback(TARGET, 1, 3, NOTE);

        assertThat(rolledBack).isEqualTo(past.withVersion(4));
        verify(repository).update(eq(rolledBack), eq(3L), eq(NOTE), any());
    }

    @Test
    @DisplayName("이력에 없는 버전으로는 되돌릴 수 없다")
    void rollbackMissingVersion() {
        when(repository.findVersion(TARGET, 9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollback(TARGET, 9, 3, NOTE)).isInstanceOf(PolicyNotFoundException.class);
    }
}
