package com.hyuk.flow_control.application.service;

import com.hyuk.flow_control.application.port.in.RequesterManagementUseCase;
import com.hyuk.flow_control.application.port.out.PolicyRepository;
import com.hyuk.flow_control.application.port.out.RequesterRepository;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.requester.Requester;
import com.hyuk.flow_control.domain.requester.RequesterHistoryEntry;
import com.hyuk.flow_control.domain.requester.RequesterNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

@Service
public class RequesterManagementService implements RequesterManagementUseCase {
    private static final Logger log = LoggerFactory.getLogger(RequesterManagementService.class);

    private final RequesterRepository repository;
    private final PolicyRepository policies;
    private final RequesterRegistry registry;

    public RequesterManagementService(RequesterRepository repository, PolicyRepository policies,
                                      RequesterRegistry registry) {
        this.repository = repository;
        this.policies = policies;
        this.registry = registry;
    }

    @Override
    public List<Requester> list() {
        return repository.loadAll();
    }

    @Override
    public Requester get(String requesterId) {
        return repository.load(requesterId)
                .orElseThrow(() -> new RequesterNotFoundException(requesterId));
    }

    @Override
    public Requester register(String requesterId, List<String> targetSystemIds, ChangeNote note) {
        Requester requester = new Requester(requesterId, 1, true, targetSystemIds);
        requester.targetSystemIds().forEach(this::requirePolicy);

        repository.insert(requester, note, Instant.now());
        log.info("요청자를 등록했습니다 : {} {} by {} ({})",
                requesterId, requester.targetSystemIds(), note.changedBy(), note.reason());
        registry.refresh();
        return requester;
    }

    @Override
    public Requester changeEnabled(String requesterId, boolean enabled, long expectedVersion, ChangeNote note) {
        return save(requesterId, expectedVersion, note, requester -> requester.withEnabled(enabled));
    }

    @Override
    public Requester addTarget(String requesterId, String targetSystemId, long expectedVersion, ChangeNote note) {
        requirePolicy(targetSystemId);
        return save(requesterId, expectedVersion, note, requester -> requester.withTarget(targetSystemId));
    }

    @Override
    public Requester removeTarget(String requesterId, String targetSystemId, long expectedVersion, ChangeNote note) {
        return save(requesterId, expectedVersion, note, requester -> requester.withoutTarget(targetSystemId));
    }

    @Override
    public List<RequesterHistoryEntry> history(String requesterId) {
        get(requesterId);
        return repository.history(requesterId);
    }

    private Requester save(String requesterId, long expectedVersion, ChangeNote note,
                           UnaryOperator<Requester> change) {
        Requester changed = change.apply(get(requesterId)).withVersion(expectedVersion + 1);

        repository.update(changed, expectedVersion, note, Instant.now());
        log.info("요청자를 바꿨습니다 : {} 버전 {} {} (사용 {}) by {} ({})", requesterId, changed.version(),
                changed.targetSystemIds(), changed.enabled(), note.changedBy(), note.reason());
        registry.refresh();
        return changed;
    }

    private void requirePolicy(String targetSystemId) {
        if (policies.load(targetSystemId).isEmpty()) {
            throw new IllegalArgumentException("정책이 없는 외부 서버입니다 : " + targetSystemId);
        }
    }
}
