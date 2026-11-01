alter table target_policy
    add column baseline_p95_ms bigint;

update target_policy
set baseline_p95_ms = baseline_rtt_ms * 2;

alter table target_policy
    alter column baseline_p95_ms set not null,
    add constraint target_policy_baseline_p95_ms_check check (baseline_p95_ms > 0);

alter table target_policy_history
    add column baseline_p95_ms bigint;

update target_policy_history
set baseline_p95_ms = baseline_rtt_ms * 2;

alter table target_policy_history
    alter column baseline_p95_ms set not null;

alter table policy_dry_run
    add column baseline_p95_ms bigint;

update policy_dry_run
set baseline_p95_ms = baseline_rtt_ms * 2;

alter table policy_dry_run
    alter column baseline_p95_ms set not null,
    add constraint policy_dry_run_baseline_p95_ms_check check (baseline_p95_ms > 0);
