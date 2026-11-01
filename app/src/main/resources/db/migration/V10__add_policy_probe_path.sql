alter table target_policy
    add column probe_path varchar(2048) not null default '/health';

alter table target_policy_history
    add column probe_path varchar(2048) not null default '/health';

alter table policy_dry_run
    add column probe_path varchar(2048) not null default '/health';
