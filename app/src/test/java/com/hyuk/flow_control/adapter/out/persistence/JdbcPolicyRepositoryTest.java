package com.hyuk.flow_control.adapter.out.persistence;

import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.PolicyAlreadyExistsException;
import com.hyuk.flow_control.domain.policy.PolicyHistoryEntry;
import com.hyuk.flow_control.domain.policy.PolicyVersionConflictException;
import com.hyuk.flow_control.domain.policy.ResponseRuleSet;
import com.hyuk.flow_control.domain.request.OutcomeKind;
import com.hyuk.flow_control.support.Policies;
import com.hyuk.flow_control.support.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcPolicyRepositoryTest {
    private static final ChangeNote NOTE = new ChangeNote("hyuk", "테스트");
    private static final Instant AT = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private JdbcClient jdbcClient;
    private JdbcPolicyRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresTestDatabase.dataSource();
        PostgresTestDatabase.reset(dataSource);
        jdbcClient = JdbcClient.create(dataSource);
        repository = new JdbcPolicyRepository(jdbcClient, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("Flyway 로 넣은 처음 정책 세 개를 읽는다")
    void loadsInitialPolicies() {
        List<LoadedPolicy> loaded = repository.loadAll();

        assertThat(loaded).extracting(LoadedPolicy::targetSystemId).containsExactly("target-a", "target-b", "target-c");
        assertThat(loaded).allMatch(policy -> policy instanceof LoadedPolicy.Valid);

        ManagedPolicy targetA = ((LoadedPolicy.Valid) loaded.getFirst()).policy();
        assertThat(targetA.version()).isEqualTo(1);
        assertThat(targetA.limits().concurrencyLimit()).isEqualTo(15);
        assertThat(targetA.response().resultField()).isEqualTo("/resultCode");
        assertThat(repository.history("target-a")).hasSize(1);
    }

    @Test
    @DisplayName("추가한 정책을 그대로 읽고, 이력에도 남긴다")
    void insertsAndLoads() {
        ManagedPolicy policy = Policies.policy("target-d");

        repository.insert(policy, NOTE, AT);

        assertThat(repository.load("target-d")).contains(new LoadedPolicy.Valid(policy));
        List<PolicyHistoryEntry> history = repository.history("target-d");
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().note()).isEqualTo(NOTE);
        assertThat(history.getFirst().changedAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("결과 코드 표가 있는 정책도 그대로 저장하고 읽는다")
    void storesResultCodes() {
        ManagedPolicy policy = Policies.withResponse(Policies.policy("target-d"), new ResponseRuleSet(
                Map.of("2xx", OutcomeKind.SUCCESS, "503", OutcomeKind.UNDER_MAINTENANCE),
                "/header/resultCode",
                Map.of("0000", OutcomeKind.SUCCESS, "E100", OutcomeKind.BUSINESS_REJECTED)));

        repository.insert(policy, NOTE, AT);

        assertThat(repository.load("target-d")).contains(new LoadedPolicy.Valid(policy));
    }

    @Test
    @DisplayName("이미 있는 대상을 추가하면 PolicyAlreadyExistsException 이다")
    void rejectsDuplicate() {
        assertThatThrownBy(() -> repository.insert(Policies.policy("target-a"), NOTE, AT))
                .isInstanceOf(PolicyAlreadyExistsException.class);
    }

    @Test
    @DisplayName("보낸 버전이 지금 버전일 때만 바꾸고, 이력은 최신 버전부터 보여준다")
    void updatesWithExpectedVersion() {
        ManagedPolicy changed = Policies.policy("target-a", 2).withEnabled(false);

        repository.update(changed, 1, NOTE, AT);

        assertThat(repository.load("target-a")).contains(new LoadedPolicy.Valid(changed));
        assertThat(repository.history("target-a")).extracting(entry -> entry.policy().version()).containsExactly(2L, 1L);
        assertThat(repository.findVersion("target-a", 2)).contains(changed);
    }

    @Test
    @DisplayName("보낸 버전이 지금 버전이 아니면 PolicyVersionConflictException 이고 아무것도 바꾸지 않는다")
    void rejectsStaleVersion() {
        repository.update(Policies.policy("target-a", 2), 1, NOTE, AT);

        assertThatThrownBy(() -> repository.update(Policies.policy("target-a", 2), 1, NOTE, AT))
                .isInstanceOf(PolicyVersionConflictException.class);
        assertThat(repository.history("target-a")).hasSize(2);
    }

    @Test
    @DisplayName("이력에 없는 버전은 찾지 못한다")
    void findsMissingVersion() {
        assertThat(repository.findVersion("target-a", 9)).isEmpty();
    }

    @Test
    @DisplayName("DB 에 잘못 저장된 정책은 Invalid 로 읽는다")
    void loadsBrokenRowAsInvalid() {
        jdbcClient.sql("update target_policy set status_codes = cast('{\"200\":\"NOPE\"}' as jsonb) where target_system_id = 'target-b'")
                .update();

        LoadedPolicy loaded = repository.load("target-b").orElseThrow();

        assertThat(loaded).isInstanceOf(LoadedPolicy.Invalid.class);
        assertThat(loaded.version()).isEqualTo(1);
    }
}
