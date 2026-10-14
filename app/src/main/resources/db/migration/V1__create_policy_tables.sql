create table target_policy
(
    target_system_id   varchar(100)     not null primary key,
    version            bigint           not null check (version >= 1),
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
    changed_at         timestamptz      not null,
    changed_by         varchar(100)     not null,
    change_reason      varchar(500)     not null
);

create table target_policy_history
(
    target_system_id   varchar(100)     not null,
    version            bigint           not null,
    enabled            boolean          not null,
    url                varchar(2048)    not null,
    connect_timeout_ms bigint           not null,
    read_timeout_ms    bigint           not null,
    max_tps            integer          not null,
    baseline_rtt_ms    bigint           not null,
    safety_margin      double precision not null,
    burst_window_ms    bigint           not null,
    status_codes       jsonb            not null,
    result_field       varchar(500),
    result_codes       jsonb,
    changed_at         timestamptz      not null,
    changed_by         varchar(100)     not null,
    change_reason      varchar(500)     not null,
    primary key (target_system_id, version)
);
