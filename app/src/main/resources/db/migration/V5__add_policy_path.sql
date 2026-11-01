alter table target_policy
    add column path varchar(2048) not null default '/call';

alter table target_policy_history
    add column path varchar(2048) not null default '/call';

alter table policy_dry_run
    add column path varchar(2048) not null default '/call';
