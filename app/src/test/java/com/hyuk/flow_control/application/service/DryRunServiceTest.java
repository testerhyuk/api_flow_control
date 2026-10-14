package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.DryRunStatus;
import com.hyuk.flow_control.application.port.in.PolicyContent;
import com.hyuk.flow_control.application.port.out.DryRunRepository;
import com.hyuk.flow_control.application.port.out.PolicyRepository;
import com.hyuk.flow_control.config.PolicyProperties;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunNotFoundException;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.ConnectionSettings;
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
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DryRunServiceTest {
    private static final String TARGET = "target-a";
    private static final ChangeNote NOTE = new ChangeNote("hyuk", "baseline 조정");
    private static final ManagedPolicy CURRENT = Policies.policy(TARGET, 3);
    private static final TargetSystemPolicy NEW_LIMITS =
            new TargetSystemPolicy(80, Duration.ofMillis(90), 0.95, Duration.ofMillis(10));
    private static final PolicyContent CONTENT =
            new PolicyContent(true, Policies.CONNECTION, NEW_LIMITS, Policies.STATUS_RULES);

    private DryRunRepository dryRuns;
    private PolicyRepository policies;
    private DryRunMonitor monitor;
    private DryRunService service;

    @BeforeEach
    void setUp() {
        dryRuns = mock(DryRunRepository.class);
        policies = mock(PolicyRepository.class);
        monitor = mock(DryRunMonitor.class);
        service = new DryRunService(dryRuns, policies, monitor,
                new PolicyProperties(Duration.ofSeconds(5), Duration.ofMinutes(30)));
        when(policies.load(TARGET)).thenReturn(Optional.of(new LoadedPolicy.Valid(CURRENT)));
    }

    @Test
    @DisplayName("시작하면 다음 버전의 새 정책을 30분짜리 dry-run 으로 저장한다")
    void startsDryRun() {
        DryRun started = service.start(TARGET, CONTENT, 3, NOTE);

        assertThat(started.baseVersion()).isEqualTo(3);
        assertThat(started.candidate().version()).isEqualTo(4);
        assertThat(started.candidate().limits()).isEqualTo(NEW_LIMITS);
        assertThat(Duration.between(started.startedAt(), started.expiresAt())).isEqualTo(Duration.ofMinutes(30));
        verify(dryRuns).insert(started);
    }

    @Test
    @DisplayName("보낸 버전이 지금 버전과 다르면 시작하지 않는다")
    void rejectsStaleVersion() {
        assertThatThrownBy(() -> service.start(TARGET, CONTENT, 2, NOTE))
                .isInstanceOf(PolicyVersionConflictException.class);
        verify(dryRuns, never()).insert(any());
    }

    @Test
    @DisplayName("url 을 바꾸는 새 정책은 dry-run 할 수 없다")
    void rejectsUrlChange() {
        PolicyContent content = new PolicyContent(true,
                new ConnectionSettings("http://other:9001", Duration.ofSeconds(1), Duration.ofSeconds(3)),
                NEW_LIMITS, Policies.STATUS_RULES);

        assertThatThrownBy(() -> service.start(TARGET, content, 3, NOTE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("enabled 를 바꾸는 새 정책은 dry-run 할 수 없다")
    void rejectsEnabledChange() {
        PolicyContent content = new PolicyContent(false, Policies.CONNECTION, NEW_LIMITS, Policies.STATUS_RULES);

        assertThatThrownBy(() -> service.start(TARGET, content, 3, NOTE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("없는 정책은 dry-run 할 수 없다")
    void rejectsMissingPolicy() {
        when(policies.load("target-z")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start("target-z", CONTENT, 1, NOTE))
                .isInstanceOf(PolicyNotFoundException.class);
    }

    @Test
    @DisplayName("저장된 정책이 잘못돼 있으면 dry-run 할 수 없다")
    void rejectsInvalidStoredPolicy() {
        when(policies.load(TARGET)).thenReturn(Optional.of(new LoadedPolicy.Invalid(TARGET, 3, "bad")));

        assertThatThrownBy(() -> service.start(TARGET, CONTENT, 3, NOTE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("조회하면 저장된 dry-run 과 이 서버의 결과를 함께 돌려준다")
    void getsStatusWithLocalReport() {
        DryRun dryRun = activeDryRun();
        when(dryRuns.find(TARGET)).thenReturn(Optional.of(dryRun));
        when(monitor.reportOf(dryRun)).thenReturn(Optional.empty());

        DryRunStatus status = service.get(TARGET);

        assertThat(status.dryRun()).isEqualTo(dryRun);
        assertThat(status.report()).isNull();
    }

    @Test
    @DisplayName("끝 시각이 지난 dry-run 은 없는 것으로 본다")
    void treatsExpiredAsMissing() {
        Instant past = Instant.now().minus(Duration.ofMinutes(31));
        DryRun expired = new DryRun(CURRENT.withVersion(4), 3, past, past.plus(Duration.ofMinutes(30)), NOTE);
        when(dryRuns.find(TARGET)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.get(TARGET)).isInstanceOf(DryRunNotFoundException.class);
    }

    @Test
    @DisplayName("없는 dry-run 은 취소할 수 없다")
    void cancelMissing() {
        when(dryRuns.delete(TARGET)).thenReturn(false);

        assertThatThrownBy(() -> service.cancel(TARGET)).isInstanceOf(DryRunNotFoundException.class);
    }

    @Test
    @DisplayName("적용하면 새 정책을 기준 버전일 때만 저장하고 dry-run 을 지운다")
    void appliesCandidate() {
        DryRun dryRun = activeDryRun();
        when(dryRuns.find(TARGET)).thenReturn(Optional.of(dryRun));

        ManagedPolicy applied = service.apply(TARGET, NOTE);

        assertThat(applied).isEqualTo(dryRun.candidate());
        verify(policies).update(eq(dryRun.candidate()), eq(3L), eq(NOTE), any());
        verify(dryRuns).delete(TARGET);
    }

    @Test
    @DisplayName("그사이 정책이 바뀌었으면 적용하지 않고 dry-run 을 지운다")
    void cancelsWhenPolicyChanged() {
        DryRun dryRun = activeDryRun();
        when(dryRuns.find(TARGET)).thenReturn(Optional.of(dryRun));
        doThrow(new PolicyVersionConflictException(TARGET, 3)).when(policies).update(any(), eq(3L), any(), any());

        assertThatThrownBy(() -> service.apply(TARGET, NOTE)).isInstanceOf(PolicyVersionConflictException.class);
        verify(dryRuns).delete(TARGET);
    }

    private static DryRun activeDryRun() {
        Instant now = Instant.now();
        return new DryRun(Policies.withLimits(CURRENT.withVersion(4), NEW_LIMITS), 3, now, now.plus(Duration.ofMinutes(30)), NOTE);
    }
}
