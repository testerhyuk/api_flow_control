create table requester
(
    requester_id      varchar(100) not null primary key check (requester_id ~ '^[a-zA-Z0-9-]{1,100}$'),
    version           bigint       not null check (version >= 1),
    enabled           boolean      not null,
    target_system_ids jsonb        not null check (jsonb_typeof(target_system_ids) = 'array'
                                                   and jsonb_array_length(target_system_ids) > 0),
    changed_at        timestamptz  not null,
    changed_by        varchar(100) not null,
    change_reason     varchar(500) not null
);

create table requester_history
(
    requester_id      varchar(100) not null,
    version           bigint       not null,
    enabled           boolean      not null,
    target_system_ids jsonb        not null,
    changed_at        timestamptz  not null,
    changed_by        varchar(100) not null,
    change_reason     varchar(500) not null,
    primary key (requester_id, version)
);
