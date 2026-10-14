package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.application.port.in.PolicyManagementUseCase;
import com.hyuk.flow_control.domain.policy.ManagedPolicy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/admin/policies")
public class PolicyAdminController {
    private final PolicyManagementUseCase management;

    public PolicyAdminController(PolicyManagementUseCase management) {
        this.management = management;
    }

    @GetMapping
    public List<PolicyViews.Policy> list() {
        return management.list().stream()
                .map(PolicyViews.Policy::from)
                .toList();
    }

    @GetMapping("/{targetSystemId}")
    public PolicyViews.Policy get(@PathVariable String targetSystemId) {
        return PolicyViews.Policy.from(management.get(targetSystemId));
    }

    @PostMapping
    public ResponseEntity<PolicyViews.Policy> create(@RequestBody PolicyRequests.Create request) {
        ManagedPolicy created = management.create(
                request.targetSystemId(), request.requiredPolicy().toContent(), request.note());

        return ResponseEntity.created(URI.create("/admin/policies/" + created.targetSystemId()))
                .body(PolicyViews.Policy.from(created));
    }

    @PutMapping("/{targetSystemId}")
    public PolicyViews.Policy update(@PathVariable String targetSystemId,
                                     @RequestBody PolicyRequests.Update request) {
        return PolicyViews.Policy.from(management.update(
                targetSystemId,
                request.requiredPolicy().toContent(),
                request.requiredExpectedVersion(),
                request.note()));
    }

    @PutMapping("/{targetSystemId}/enabled")
    public PolicyViews.Policy changeEnabled(@PathVariable String targetSystemId,
                                            @RequestBody PolicyRequests.ChangeEnabled request) {
        return PolicyViews.Policy.from(management.changeEnabled(
                targetSystemId,
                request.requiredEnabled(),
                request.requiredExpectedVersion(),
                request.note()));
    }

    @GetMapping("/{targetSystemId}/history")
    public List<PolicyViews.History> history(@PathVariable String targetSystemId) {
        return management.history(targetSystemId).stream()
                .map(PolicyViews.History::from)
                .toList();
    }

    @PostMapping("/{targetSystemId}/rollback")
    public PolicyViews.Policy rollback(@PathVariable String targetSystemId,
                                       @RequestBody PolicyRequests.Rollback request) {
        return PolicyViews.Policy.from(management.rollback(
                targetSystemId,
                request.requiredToVersion(),
                request.requiredExpectedVersion(),
                request.note()));
    }
}
