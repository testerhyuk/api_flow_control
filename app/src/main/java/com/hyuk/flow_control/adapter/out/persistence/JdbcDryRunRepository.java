package com.hyuk.flow_control.adapter.out.persistence;

import com.hyuk.flow_control.application.port.out.DryRunRepository;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunAlreadyRunningException;
import com.hyuk.flow_control.domain.policy.ChangeNote;
import com.hyuk.flow_control.domain.policy.PolicyVersionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcDryRunRepository implements DryRunRepository {
    private static final Logger log = LoggerFactory.getLogger(JdbcDryRunRepository.class);

    private static final String COLUMNS = PolicyColumns.NAMES
            + ", base_version, started_at, expires_at, started_by, start_reason";

    private final JdbcClient jdbcClient;
    private final PolicyColumns policyColumns;

    public JdbcDryRunRepository(JdbcClient jdbcClient, JsonMapper jsonMapper) {
        this.jdbcClient = jdbcClient;
        this.policyColumns = new PolicyColumns(jsonMapper);
    }

    @Override
    public List<DryRun> loadAll() {
        return jdbcClient.sql("select " + COLUMNS + " from policy_dry_run order by target_system_id")
                .query((rs, rowNum) -> toDryRunOrNull(rs))
                .list()
                .stream()
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<DryRun> find(String targetSystemId) {
        return jdbcClient.sql("select " + COLUMNS + " from policy_dry_run where target_system_id = :id")
                .param("id", targetSystemId)
                .query((rs, rowNum) -> toDryRunOrNull(rs))
                .list()
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    @Override
    public void insert(DryRun dryRun) {
        int inserted;
        try {
            inserted = policyColumns.bind(jdbcClient.sql("insert into policy_dry_run (" + COLUMNS + ") select "
                                    + PolicyColumns.VALUES + """
                                    , :baseVersion, :startedAt, :expiresAt, :startedBy, :startReason
                                    where exists (select 1 from target_policy
                                                  where target_system_id = :targetSystemId and version = :baseVersion)
                                    """),
                            dryRun.candidate())
                    .param("baseVersion", dryRun.baseVersion())
                    .param("startedAt", Timestamp.from(dryRun.startedAt()))
                    .param("expiresAt", Timestamp.from(dryRun.expiresAt()))
                    .param("startedBy", dryRun.note().changedBy())
                    .param("startReason", dryRun.note().reason())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DryRunAlreadyRunningException(dryRun.targetSystemId());
        }

        if (inserted == 0) {
            throw new PolicyVersionConflictException(dryRun.targetSystemId(), dryRun.baseVersion());
        }
    }

    @Override
    public boolean delete(String targetSystemId) {
        return jdbcClient.sql("delete from policy_dry_run where target_system_id = :id")
                .param("id", targetSystemId)
                .update() > 0;
    }

    @Override
    public int deleteStale(Instant now) {
        return jdbcClient.sql("""
                        delete from policy_dry_run d
                        where d.expires_at <= :now
                           or not exists (select 1 from target_policy p
                                          where p.target_system_id = d.target_system_id
                                            and p.version = d.base_version)
                        """)
                .param("now", Timestamp.from(now))
                .update();
    }

    private DryRun toDryRunOrNull(ResultSet rs) throws SQLException {
        long baseVersion = rs.getLong("base_version");

        try {
            return new DryRun(
                    policyColumns.read(rs, baseVersion + 1),
                    baseVersion,
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    new ChangeNote(rs.getString("started_by"), rs.getString("start_reason")));
        } catch (IllegalArgumentException | JacksonException e) {
            log.warn("잘못 저장된 dry-run 이라 건너뜁니다 : {} ({})", rs.getString("target_system_id"), e.getMessage());
            return null;
        }
    }
}
