insert into target_policy (target_system_id, version, enabled, url, connect_timeout_ms, read_timeout_ms,
                           max_tps, baseline_rtt_ms, safety_margin, burst_window_ms,
                           status_codes, result_field, result_codes,
                           changed_at, changed_by, change_reason)
values ('target-a', 1, true, 'http://localhost:9001', 1000, 3000,
        100, 150, 0.95, 10,
        '{"2xx": "SUCCESS", "400": "INVALID_REQUEST", "429": "TOO_MANY_REQUESTS", "5xx": "SERVER_ERROR"}',
        '/resultCode',
        '{"0000": "SUCCESS", "E100": "BUSINESS_REJECTED", "E429": "TOO_MANY_REQUESTS", "E500": "SERVER_ERROR", "E900": "UNDER_MAINTENANCE"}',
        now(), 'flyway', '처음 정책'),
       ('target-b', 1, true, 'http://localhost:9002', 1000, 3000,
        100, 150, 0.95, 10,
        '{"2xx": "SUCCESS", "400": "INVALID_REQUEST", "422": "BUSINESS_REJECTED", "429": "TOO_MANY_REQUESTS", "5xx": "SERVER_ERROR"}',
        null, null,
        now(), 'flyway', '처음 정책'),
       ('target-c', 1, true, 'http://localhost:9003', 1000, 3000,
        100, 150, 0.95, 10,
        '{"2xx": "SUCCESS", "400": "INVALID_REQUEST", "422": "BUSINESS_REJECTED", "429": "TOO_MANY_REQUESTS", "5xx": "SERVER_ERROR"}',
        null, null,
        now(), 'flyway', '처음 정책');

insert into target_policy_history
select *
from target_policy;
