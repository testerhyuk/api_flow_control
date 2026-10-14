alter table target_policy
    add column maintenance jsonb not null default '{"recurring": [], "oneTime": []}';

alter table target_policy_history
    add column maintenance jsonb not null default '{"recurring": [], "oneTime": []}';

alter table policy_dry_run
    add column maintenance jsonb not null default '{"recurring": [], "oneTime": []}';
