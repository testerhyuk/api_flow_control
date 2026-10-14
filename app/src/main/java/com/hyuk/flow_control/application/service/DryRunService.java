package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.DryRunStatus;
import com.hyuk.flow_control.application.port.in.DryRunUseCase;
import com.hyuk.flow_control.application.port.in.PolicyContent;
import com.hyuk.flow_control.application.port.out.DryRunRepository;
import com.hyuk.flow_control.application.port.out.PolicyRepository;
import com.hyuk.flow_control.config.PolicyProperties;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunNotFoundException;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.PolicyNotFoundException;
import com.hyuk.flow_control.domain.policy.PolicyVersionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class DryRunService implements DryRunUseCase {
    private static final Logger log = LoggerFactory.getLogger(DryRunService.class);

    private final DryRunRepository dryRuns;
    private final PolicyRepository policies;
    private final DryRunMonitor monitor;
    private final Duration maxDuration;

    public DryRunService(DryRunRepository dryRuns, PolicyRepository policies, DryRunMonitor monitor,
                         PolicyProperties policyProperties) {
        this.dryRuns = dryRuns;
        this.policies = policies;
        this.monitor = monitor;
        this.maxDuration = policyProperties.dryRunMaxDuration();
    }

    @Override
    public DryRun start(String targetSystemId, PolicyContent content, long expectedVersion, ChangeNote note) {
        ManagedPolicy current = validPolicyOf(targetSystemId);

        if (current.version() != expectedVersion) {
            throw new PolicyVersionConflictException(targetSystemId, expectedVersion);
        }

        ManagedPolicy candidate = content.toPolicy(targetSystemId, expectedVersion + 1);

        if (!candidate.connection().url().equals(current.connection().url())) {
            throw new IllegalArgumentException("url 을 바꾸는 정책은 dry-run 할 수 없습니다 : " + targetSystemId);
        }
        if (candidate.enabled() != current.enabled()) {
            throw new IllegalArgumentException("enabled 는 dry-run 으로 바꿀 수 없습니다. 켜고 끄기 API 를 써 주세요 : " + targetSystemId);
        }

        Instant now = Instant.now();
        DryRun dryRun = new DryRun(candidate, expectedVersion, now, now.plus(maxDuration), note);

        dryRuns.insert(dryRun);
        log.info("dry-run 을 등록했습니다 : {} 버전 {} 기준 by {} ({})",
                targetSystemId, expectedVersion, note.changedBy(), note.reason());
        return dryRun;
    }

    @Override
    public DryRunStatus get(String targetSystemId) {
        DryRun dryRun = activeDryRunOf(targetSystemId);
        return new DryRunStatus(dryRun, monitor.reportOf(dryRun).orElse(null));
    }

    @Override
    public void cancel(String targetSystemId) {
        if (!dryRuns.delete(targetSystemId)) {
            throw new DryRunNotFoundException(targetSystemId);
        }
        log.info("dry-run 을 취소했습니다 : {}", targetSystemId);
    }

    @Override
    public ManagedPolicy apply(String targetSystemId, ChangeNote note) {
        DryRun dryRun = activeDryRunOf(targetSystemId);
        ManagedPolicy candidate = dryRun.candidate();

        try {
            policies.update(candidate, dryRun.baseVersion(), note, Instant.now());
        } catch (PolicyVersionConflictException e) {
            dryRuns.delete(targetSystemId);
            log.info("정책이 그사이 바뀌어서 dry-run 을 취소했습니다 : {}", targetSystemId);
            throw e;
        }

        dryRuns.delete(targetSystemId);
        log.info("dry-run 정책을 적용했습니다 : {} 버전 {} by {} ({})",
                targetSystemId, candidate.version(), note.changedBy(), note.reason());
        return candidate;
    }

    private ManagedPolicy validPolicyOf(String targetSystemId) {
        LoadedPolicy loaded = policies.load(targetSystemId)
                .orElseThrow(() -> new PolicyNotFoundException(targetSystemId));

        if (!(loaded instanceof LoadedPolicy.Valid valid)) {
            throw new IllegalArgumentException("저장된 정책이 잘못돼 있어서 dry-run 할 수 없습니다. 정책 전체를 고쳐 주세요 : " + targetSystemId);
        }

        return valid.policy();
    }

    private DryRun activeDryRunOf(String targetSystemId) {
        return dryRuns.find(targetSystemId)
                .filter(dryRun -> !dryRun.isExpiredAt(Instant.now()))
                .orElseThrow(() -> new DryRunNotFoundException(targetSystemId));
    }
}
