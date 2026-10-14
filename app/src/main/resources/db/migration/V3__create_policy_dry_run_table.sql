create table policy_dry_run
(
    target_system_id   varchar(100)     not null primary key references target_policy (target_system_id),
    base_version       bigint           not null check (base_version >= 1),
    enabled            boolean          not null,
    url                varchar(2048)    not null,
    connect_timeout_ms bigint           not null check (connect_timeout_ms > 0),
    read_timeout_ms    bigint           not null check (read_timeout_ms > 0),
    max_tps            integer          not null check (max_tps > 0),
    baseline_rtt_ms    bigint           not null check (baseline_rtt_ms > 0),
    safety_margin      double precision not null check (safety_margin > 0 and safety_margin <= 1),
    burst_window_ms    bigint           not null check (burst_window_ms > 0),
    status_codes       jsonb            not null,
    result_field       varchar(500),
    result_codes       jsonb,
    started_at         timestamptz      not null,
    expires_at         timestamptz      not null check (expires_at > started_at),
    started_by         varchar(100)     not null,
    start_reason       varchar(500)     not null
);
