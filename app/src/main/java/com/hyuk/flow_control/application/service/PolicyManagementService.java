package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.PolicyContent;
import com.hyuk.flow_control.application.port.in.PolicyManagementUseCase;
import com.hyuk.flow_control.application.port.out.PolicyRepository;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.PolicyHistoryEntry;
import com.hyuk.flow_control.domain.policy.PolicyNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class PolicyManagementService implements PolicyManagementUseCase {
    private static final Logger log = LoggerFactory.getLogger(PolicyManagementService.class);

    private final PolicyRepository repository;

    public PolicyManagementService(PolicyRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<LoadedPolicy> list() {
        return repository.loadAll();
    }

    @Override
    public LoadedPolicy get(String targetSystemId) {
        return repository.load(targetSystemId)
                .orElseThrow(() -> new PolicyNotFoundException(targetSystemId));
    }

    @Override
    public ManagedPolicy create(String targetSystemId, PolicyContent content, ChangeNote note) {
        ManagedPolicy policy = content.toPolicy(targetSystemId, 1);

        repository.insert(policy, note, Instant.now());
        log.info("정책을 추가했습니다 : {} by {} ({})", targetSystemId, note.changedBy(), note.reason());
        return policy;
    }

    @Override
    public ManagedPolicy update(String targetSystemId, PolicyContent content, long expectedVersion, ChangeNote note) {
        get(targetSystemId);
        return save(content.toPolicy(targetSystemId, expectedVersion + 1), expectedVersion, note);
    }

    @Override
    public ManagedPolicy changeEnabled(String targetSystemId, boolean enabled, long expectedVersion, ChangeNote note) {
        LoadedPolicy loaded = get(targetSystemId);

        if (!(loaded instanceof LoadedPolicy.Valid valid)) {
            throw new IllegalArgumentException("저장된 정책이 잘못돼 있어서 켜고 끌 수 없습니다. 정책 전체를 고쳐 주세요 : " + targetSystemId);
        }

        return save(valid.policy().withEnabled(enabled).withVersion(expectedVersion + 1), expectedVersion, note);
    }

    @Override
    public List<PolicyHistoryEntry> history(String targetSystemId) {
        get(targetSystemId);
        return repository.history(targetSystemId);
    }

    @Override
    public ManagedPolicy rollback(String targetSystemId, long toVersion, long expectedVersion, ChangeNote note) {
        ManagedPolicy past = repository.findVersion(targetSystemId, toVersion)
                .orElseThrow(() -> new PolicyNotFoundException(targetSystemId, toVersion));

        return save(past.withVersion(expectedVersion + 1), expectedVersion, note);
    }

    private ManagedPolicy save(ManagedPolicy policy, long expectedVersion, ChangeNote note) {
        repository.update(policy, expectedVersion, note, Instant.now());
        log.info("정책을 바꿨습니다 : {} 버전 {} by {} ({})",
                policy.targetSystemId(), policy.version(), note.changedBy(), note.reason());
        return policy;
    }
}
