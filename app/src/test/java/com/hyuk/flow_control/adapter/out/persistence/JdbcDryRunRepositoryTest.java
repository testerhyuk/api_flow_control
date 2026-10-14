package com.hyuk.flow_control.adapter.out.persistence;

import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunAlreadyRunningException;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.PolicyVersionConflictException;
import com.hyuk.flow_control.domain.policy.TargetSystemPolicy;
import com.hyuk.flow_control.support.Policies;
import com.hyuk.flow_control.support.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcDryRunRepositoryTest {
    private static final ChangeNote NOTE = new ChangeNote("hyuk", "baseline 조정");
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private JdbcDryRunRepository dryRuns;
    private JdbcPolicyRepository policies;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresTestDatabase.dataSource();
        PostgresTestDatabase.reset(dataSource);
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        dryRuns = new JdbcDryRunRepository(jdbcClient, jsonMapper);
        policies = new JdbcPolicyRepository(jdbcClient, jsonMapper);
    }

    @Test
    @DisplayName("저장한 dry-run 을 그대로 읽는다")
    void insertsAndFinds() {
        DryRun dryRun = dryRun("target-a", 1, NOW.plus(Duration.ofMinutes(30)));

        dryRuns.insert(dryRun);

        assertThat(dryRuns.find("target-a")).contains(dryRun);
        assertThat(dryRuns.loadAll()).containsExactly(dryRun);
    }

    @Test
    @DisplayName("대상마다 하나만 저장할 수 있다")
    void allowsOnePerTarget() {
        dryRuns.insert(dryRun("target-a", 1, NOW.plus(Duration.ofMinutes(30))));

        assertThatThrownBy(() -> dryRuns.insert(dryRun("target-a", 1, NOW.plus(Duration.ofMinutes(10)))))
                .isInstanceOf(DryRunAlreadyRunningException.class);
    }

    @Test
    @DisplayName("기준 버전이 지금 정책 버전이 아니면 저장하지 않는다")
    void rejectsStaleBaseVersion() {
        policies.update(Policies.policy("target-a", 2), 1, NOTE, NOW);

        assertThatThrownBy(() -> dryRuns.insert(dryRun("target-a", 1, NOW.plus(Duration.ofMinutes(30)))))
                .isInstanceOf(PolicyVersionConflictException.class);
        assertThat(dryRuns.find("target-a")).isEmpty();
    }

    @Test
    @DisplayName("지우면 true, 없으면 false 를 돌려준다")
    void deletes() {
        dryRuns.insert(dryRun("target-a", 1, NOW.plus(Duration.ofMinutes(30))));

        assertThat(dryRuns.delete("target-a")).isTrue();
        assertThat(dryRuns.delete("target-a")).isFalse();
    }

    @Test
    @DisplayName("끝 시각이 지났거나 정책 버전이 바뀐 dry-run 을 지운다")
    void deletesStale() {
        dryRuns.insert(dryRun("target-a", 1, NOW.plus(Duration.ofMinutes(30))));
        dryRuns.insert(dryRun("target-b", 1, NOW.plus(Duration.ofMinutes(1))));
        dryRuns.insert(dryRun("target-c", 1, NOW.plus(Duration.ofMinutes(30))));
        policies.update(Policies.policy("target-c", 2), 1, NOTE, NOW);

        int deleted = dryRuns.deleteStale(NOW.plus(Duration.ofMinutes(2)));

        assertThat(deleted).isEqualTo(2);
        assertThat(dryRuns.loadAll()).extracting(DryRun::targetSystemId).containsExactly("target-a");
    }

    private static DryRun dryRun(String targetSystemId, long baseVersion, Instant expiresAt) {
        ManagedPolicy candidate = Policies.withLimits(Policies.policy(targetSystemId, baseVersion + 1),
                new TargetSystemPolicy(80, Duration.ofMillis(90), 0.95, Duration.ofMillis(10)));
        return new DryRun(candidate, baseVersion, expiresAt.minus(Duration.ofMinutes(30)), expiresAt, NOTE);
    }
}
