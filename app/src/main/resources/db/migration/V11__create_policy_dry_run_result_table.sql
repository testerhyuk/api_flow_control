create table policy_dry_run_result
(
    id                 bigserial        primary key,
    target_system_id   varchar(100)     not null,
    base_version       bigint           not null,
    enabled            boolean          not null,
    url                varchar(2048)    not null,
    path               varchar(2048)    not null,
    probe_path         varchar(2048)    not null,
    connect_timeout_ms bigint           not null,
    read_timeout_ms    bigint           not null,
    max_tps            integer          not null,
    baseline_rtt_ms    bigint           not null,
    baseline_p95_ms    bigint           not null,
    safety_margin      double precision not null,
    burst_window_ms    bigint           not null,
    status_codes       jsonb            not null,
    result_field       varchar(500),
    result_codes       jsonb,
    maintenance        jsonb            not null,
    started_at         timestamptz      not null,
    expires_at         timestamptz      not null,
    started_by         varchar(100)     not null,
    start_reason       varchar(500)     not null,
    ended_at           timestamptz      not null,
    end_reason         varchar(20)      not null check (end_reason in ('APPLIED', 'CANCELLED', 'EXPIRED', 'POLICY_CHANGED')),
    ended_by           varchar(100),
    applied_version    bigint,
    report             jsonb
);

create index policy_dry_run_result_target_ended_at on policy_dry_run_result (target_system_id, ended_at desc);
