package com.hyuk.flow_control.adapter.out.persistence;

import com.hyuk.flow_control.application.port.out.PolicyRepository;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.LoadedPolicy;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import com.hyuk.flow_control.domain.policy.PolicyAlreadyExistsException;
import com.hyuk.flow_control.domain.policy.PolicyHistoryEntry;
import com.hyuk.flow_control.domain.policy.PolicyVersionConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcPolicyRepository implements PolicyRepository {
    private static final String COLUMNS = PolicyColumns.NAMES + ", version, changed_at, changed_by, change_reason";
    private static final String VALUES = PolicyColumns.VALUES + ", :version, :changedAt, :changedBy, :changeReason";

    private final JdbcClient jdbcClient;
    private final PolicyColumns policyColumns;

    public JdbcPolicyRepository(JdbcClient jdbcClient, JsonMapper jsonMapper) {
        this.jdbcClient = jdbcClient;
        this.policyColumns = new PolicyColumns(jsonMapper);
    }

    @Override
    public List<LoadedPolicy> loadAll() {
        return jdbcClient.sql("select " + COLUMNS + " from target_policy order by target_system_id")
                .query((rs, rowNum) -> toLoaded(rs))
                .list();
    }

    @Override
    public Optional<LoadedPolicy> load(String targetSystemId) {
        return jdbcClient.sql("select " + COLUMNS + " from target_policy where target_system_id = :id")
                .param("id", targetSystemId)
                .query((rs, rowNum) -> toLoaded(rs))
                .optional();
    }

    @Override
    @Transactional
    public void insert(ManagedPolicy policy, ChangeNote note, Instant changedAt) {
        try {
            bind(jdbcClient.sql("insert into target_policy (" + COLUMNS + ") values (" + VALUES + ")"),
                    policy, note, changedAt)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new PolicyAlreadyExistsException(policy.targetSystemId());
        }
        insertHistory(policy, note, changedAt);
    }

    @Override
    @Transactional
    public void update(ManagedPolicy policy, long expectedVersion, ChangeNote note, Instant changedAt) {
        int updated = bind(jdbcClient.sql("update target_policy set " + PolicyColumns.ASSIGNMENTS + """
                        , version = :version,
                        changed_at = :changedAt, changed_by = :changedBy, change_reason = :changeReason
                        where target_system_id = :targetSystemId and version = :expectedVersion
                        """), policy, note, changedAt)
                .param("expectedVersion", expectedVersion)
                .update();

        if (updated == 0) {
            throw new PolicyVersionConflictException(policy.targetSystemId(), expectedVersion);
        }
        insertHistory(policy, note, changedAt);
    }

    @Override
    public List<PolicyHistoryEntry> history(String targetSystemId) {
        return jdbcClient.sql("select " + COLUMNS
                        + " from target_policy_history where target_system_id = :id order by version desc")
                .param("id", targetSystemId)
                .query((rs, rowNum) -> new PolicyHistoryEntry(
                        toPolicy(rs),
                        rs.getTimestamp("changed_at").toInstant(),
                        new ChangeNote(rs.getString("changed_by"), rs.getString("change_reason"))))
                .list();
    }

    @Override
    public Optional<ManagedPolicy> findVersion(String targetSystemId, long version) {
        return jdbcClient.sql("select " + COLUMNS
                        + " from target_policy_history where target_system_id = :id and version = :version")
                .param("id", targetSystemId)
                .param("version", version)
                .query((rs, rowNum) -> toPolicy(rs))
                .optional();
    }

    private void insertHistory(ManagedPolicy policy, ChangeNote note, Instant changedAt) {
        bind(jdbcClient.sql("insert into target_policy_history (" + COLUMNS + ") values (" + VALUES + ")"),
                policy, note, changedAt)
                .update();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, ManagedPolicy policy,
                                          ChangeNote note, Instant changedAt) {
        return policyColumns.bind(statement, policy)
                .param("version", policy.version())
                .param("changedAt", Timestamp.from(changedAt))
                .param("changedBy", note.changedBy())
                .param("changeReason", note.reason());
    }

    private LoadedPolicy toLoaded(ResultSet rs) throws SQLException {
        try {
            return new LoadedPolicy.Valid(toPolicy(rs));
        } catch (IllegalArgumentException | JacksonException e) {
            return new LoadedPolicy.Invalid(rs.getString("target_system_id"), rs.getLong("version"), e.getMessage());
        }
    }

    private ManagedPolicy toPolicy(ResultSet rs) throws SQLException {
        return policyColumns.read(rs, rs.getLong("version"));
    }
}
