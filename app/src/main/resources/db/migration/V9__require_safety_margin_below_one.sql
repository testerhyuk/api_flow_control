alter table target_policy
    drop constraint target_policy_safety_margin_check,
    add constraint target_policy_safety_margin_check check (safety_margin > 0 and safety_margin < 1);

alter table policy_dry_run
    drop constraint policy_dry_run_safety_margin_check,
    add constraint policy_dry_run_safety_margin_check check (safety_margin > 0 and safety_margin < 1);
