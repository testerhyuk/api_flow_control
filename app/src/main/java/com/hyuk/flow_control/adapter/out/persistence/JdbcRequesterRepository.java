package com.hyuk.flow_control.adapter.out.persistence;

import com.hyuk.flow_control.application.port.out.RequesterRepository;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.requester.Requester;
import com.hyuk.flow_control.domain.requester.RequesterAlreadyExistsException;
import com.hyuk.flow_control.domain.requester.RequesterHistoryEntry;
import com.hyuk.flow_control.domain.requester.RequesterVersionConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRequesterRepository implements RequesterRepository {
    private static final TypeReference<List<String>> TARGETS = new TypeReference<>() {
    };
    private static final String COLUMNS =
            "requester_id, version, enabled, target_system_ids, changed_at, changed_by, change_reason";
    private static final String VALUES =
            ":requesterId, :version, :enabled, cast(:targetSystemIds as jsonb), :changedAt, :changedBy, :changeReason";

    private final JdbcClient jdbcClient;
    private final JsonMapper jsonMapper;

    public JdbcRequesterRepository(JdbcClient jdbcClient, JsonMapper jsonMapper) {
        this.jdbcClient = jdbcClient;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public List<Requester> loadAll() {
        return jdbcClient.sql("select " + COLUMNS + " from requester order by requester_id")
                .query((rs, rowNum) -> toRequester(rs))
                .list();
    }

    @Override
    public Optional<Requester> load(String requesterId) {
        return jdbcClient.sql("select " + COLUMNS + " from requester where requester_id = :id")
                .param("id", requesterId)
                .query((rs, rowNum) -> toRequester(rs))
                .optional();
    }

    @Override
    @Transactional
    public void insert(Requester requester, ChangeNote note, Instant changedAt) {
        try {
            bind(jdbcClient.sql("insert into requester (" + COLUMNS + ") values (" + VALUES + ")"),
                    requester, note, changedAt)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new RequesterAlreadyExistsException(requester.requesterId());
        }
        insertHistory(requester, note, changedAt);
    }

    @Override
    @Transactional
    public void update(Requester requester, long expectedVersion, ChangeNote note, Instant changedAt) {
        int updated = bind(jdbcClient.sql("""
                        update requester set version = :version, enabled = :enabled,
                        target_system_ids = cast(:targetSystemIds as jsonb),
                        changed_at = :changedAt, changed_by = :changedBy, change_reason = :changeReason
                        where requester_id = :requesterId and version = :expectedVersion
                        """), requester, note, changedAt)
                .param("expectedVersion", expectedVersion)
                .update();

        if (updated == 0) {
            throw new RequesterVersionConflictException(requester.requesterId(), expectedVersion);
        }
        insertHistory(requester, note, changedAt);
    }

    @Override
    public List<RequesterHistoryEntry> history(String requesterId) {
        return jdbcClient.sql("select " + COLUMNS
                        + " from requester_history where requester_id = :id order by version desc")
                .param("id", requesterId)
                .query((rs, rowNum) -> new RequesterHistoryEntry(
                        toRequester(rs),
                        rs.getTimestamp("changed_at").toInstant(),
                        new ChangeNote(rs.getString("changed_by"), rs.getString("change_reason"))))
                .list();
    }

    private void insertHistory(Requester requester, ChangeNote note, Instant changedAt) {
        bind(jdbcClient.sql("insert into requester_history (" + COLUMNS + ") values (" + VALUES + ")"),
                requester, note, changedAt)
                .update();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Requester requester,
                                          ChangeNote note, Instant changedAt) {
        return statement
                .param("requesterId", requester.requesterId())
                .param("version", requester.version())
                .param("enabled", requester.enabled())
                .param("targetSystemIds", jsonMapper.writeValueAsString(requester.targetSystemIds()))
                .param("changedAt", Timestamp.from(changedAt))
                .param("changedBy", note.changedBy())
                .param("changeReason", note.reason());
    }

    private Requester toRequester(ResultSet rs) throws SQLException {
        return new Requester(
                rs.getString("requester_id"),
                rs.getLong("version"),
                rs.getBoolean("enabled"),
                jsonMapper.readValue(rs.getString("target_system_ids"), TARGETS));
    }
}
