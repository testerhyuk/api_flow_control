package com.hyuk.flow_control.application.port.in;

import com.hyuk.flow_control.domain.dryrun.DryRun;
import com.hyuk.flow_control.domain.dryrun.DryRunReport;

public record DryRunStatus(
        DryRun dryRun,
        DryRunReport report
) {
}
