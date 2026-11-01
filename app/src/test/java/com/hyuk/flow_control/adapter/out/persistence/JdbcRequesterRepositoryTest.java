package com.hyuk.flow_control.adapter.out.persistence;

import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.requester.Requester;
import com.hyuk.flow_control.domain.requester.RequesterAlreadyExistsException;
import com.hyuk.flow_control.domain.requester.RequesterHistoryEntry;
import com.hyuk.flow_control.domain.requester.RequesterVersionConflictException;
import com.hyuk.flow_control.support.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcRequesterRepositoryTest {
    private static final ChangeNote NOTE = new ChangeNote("hyuk", "테스트");
    private static final Instant AT = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final Requester LOAN = new Requester("loan", 1, true, List.of("target-a", "target-b"));

    private JdbcClient jdbcClient;
    private JdbcRequesterRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresTestDatabase.dataSource();
        PostgresTestDatabase.reset(dataSource);
        jdbcClient = JdbcClient.create(dataSource);
        repository = new JdbcRequesterRepository(jdbcClient, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("Flyway 로 넣은 실험용 요청자를 읽는다")
    void loadsSeedRequester() {
        assertThat(repository.loadAll()).containsExactly(
                new Requester("record-ingestor", 1, true, List.of("target-a", "target-b", "target-c")));
        assertThat(repository.history("record-ingestor")).hasSize(1);
    }

    @Test
    @DisplayName("등록한 요청자를 그대로 읽고, 이력에도 남긴다")
    void insertsAndLoads() {
        repository.insert(LOAN, NOTE, AT);

        assertThat(repository.load("loan")).contains(LOAN);
        List<RequesterHistoryEntry> history = repository.history("loan");
        assertThat(history).containsExactly(new RequesterHistoryEntry(LOAN, AT, NOTE));
    }

    @Test
    @DisplayName("이미 있는 요청자는 다시 등록할 수 없다")
    void rejectsDuplicate() {
        repository.insert(LOAN, NOTE, AT);

        assertThatThrownBy(() -> repository.insert(LOAN, NOTE, AT))
                .isInstanceOf(RequesterAlreadyExistsException.class);
    }

    @Test
    @DisplayName("보낸 버전일 때만 바꾸고, 이력은 최신 버전부터 보여준다")
    void updatesWithExpectedVersion() {
        repository.insert(LOAN, NOTE, AT);
        Requester changed = LOAN.withoutTarget("target-a").withEnabled(false).withVersion(2);

        repository.update(changed, 1, NOTE, AT);

        assertThat(repository.load("loan")).contains(changed);
        assertThat(repository.history("loan")).extracting(entry -> entry.requester().version())
                .containsExactly(2L, 1L);
        assertThatThrownBy(() -> repository.update(changed.withVersion(3), 1, NOTE, AT))
                .isInstanceOf(RequesterVersionConflictException.class);
    }

    @Test
    @DisplayName("DB 도 규칙에 맞지 않는 요청자 ID 와 빈 외부 서버 목록을 막는다")
    void databaseRejectsInvalidRows() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                        insert into requester (requester_id, version, enabled, target_system_ids,
                        changed_at, changed_by, change_reason)
                        values ('loan.app', 1, true, '["target-a"]', now(), 'hyuk', '테스트')
                        """).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        insert into requester (requester_id, version, enabled, target_system_ids,
                        changed_at, changed_by, change_reason)
                        values ('loan', 1, true, '[]', now(), 'hyuk', '테스트')
                        """).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
