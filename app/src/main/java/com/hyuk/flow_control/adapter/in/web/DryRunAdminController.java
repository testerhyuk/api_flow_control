package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.application.port.in.DryRunUseCase;
import com.hyuk.flow_control.domain.dryrun.DryRun;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/admin/policies/{targetSystemId}/dry-run")
public class DryRunAdminController {
    private final DryRunUseCase dryRuns;

    public DryRunAdminController(DryRunUseCase dryRuns) {
        this.dryRuns = dryRuns;
    }

    @PostMapping
    public ResponseEntity<DryRunViews.Status> start(@PathVariable String targetSystemId,
                                                    @RequestBody PolicyRequests.StartDryRun request) {
        DryRun started = dryRuns.start(
                targetSystemId,
                request.requiredPolicy().toContent(),
                request.requiredExpectedVersion(),
                request.note());

        return ResponseEntity.created(URI.create("/admin/policies/" + targetSystemId + "/dry-run"))
                .body(DryRunViews.Status.from(started));
    }

    @GetMapping
    public DryRunViews.Status get(@PathVariable String targetSystemId) {
        return DryRunViews.Status.from(dryRuns.get(targetSystemId));
    }

    @DeleteMapping
    public ResponseEntity<Void> cancel(@PathVariable String targetSystemId) {
        dryRuns.cancel(targetSystemId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/apply")
    public PolicyViews.Policy apply(@PathVariable String targetSystemId,
                                    @RequestBody PolicyRequests.ApplyDryRun request) {
        return PolicyViews.Policy.from(dryRuns.apply(targetSystemId, request.note()));
    }
}
