insert into requester (requester_id, version, enabled, target_system_ids, changed_at, changed_by, change_reason)
values ('record-ingestor', 1, true, '["target-a", "target-b", "target-c"]', now(), 'flyway', '실험용 요청자');

insert into requester_history (requester_id, version, enabled, target_system_ids, changed_at, changed_by, change_reason)
values ('record-ingestor', 1, true, '["target-a", "target-b", "target-c"]', now(), 'flyway', '실험용 요청자');
