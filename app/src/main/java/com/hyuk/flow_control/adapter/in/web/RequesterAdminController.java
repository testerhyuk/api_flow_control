package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.application.port.in.RequesterManagementUseCase;
import com.hyuk.flow_control.domain.requester.Requester;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/admin/requesters")
public class RequesterAdminController {
    private final RequesterManagementUseCase management;

    public RequesterAdminController(RequesterManagementUseCase management) {
        this.management = management;
    }

    @GetMapping
    public List<RequesterViews.RequesterView> list() {
        return management.list().stream()
                .map(RequesterViews.RequesterView::from)
                .toList();
    }

    @GetMapping("/{requesterId}")
    public RequesterViews.RequesterView get(@PathVariable String requesterId) {
        return RequesterViews.RequesterView.from(management.get(requesterId));
    }

    @PostMapping
    public ResponseEntity<RequesterViews.RequesterView> register(@RequestBody RequesterRequests.Register request,
                                                                         @RequestAttribute(AdminApiKeyFilter.OPERATOR) String operator) {
        Requester registered = management.register(
                request.requesterId(), request.requiredTargetSystemIds(), request.note(operator));

        return ResponseEntity.created(URI.create("/admin/requesters/" + registered.requesterId()))
                .body(RequesterViews.RequesterView.from(registered));
    }

    @PutMapping("/{requesterId}/enabled")
    public RequesterViews.RequesterView changeEnabled(@PathVariable String requesterId,
                                                      @RequestBody RequesterRequests.ChangeEnabled request,
                                                      @RequestAttribute(AdminApiKeyFilter.OPERATOR) String operator) {
        return RequesterViews.RequesterView.from(management.changeEnabled(
                requesterId, request.requiredEnabled(), request.requiredExpectedVersion(), request.note(operator)));
    }

    @PutMapping("/{requesterId}/targets/{targetSystemId}")
    public RequesterViews.RequesterView addTarget(@PathVariable String requesterId,
                                                  @PathVariable String targetSystemId,
                                                  @RequestBody RequesterRequests.ChangeTarget request,
                                                  @RequestAttribute(AdminApiKeyFilter.OPERATOR) String operator) {
        return RequesterViews.RequesterView.from(management.addTarget(
                requesterId, targetSystemId, request.requiredExpectedVersion(), request.note(operator)));
    }

    @DeleteMapping("/{requesterId}/targets/{targetSystemId}")
    public RequesterViews.RequesterView removeTarget(@PathVariable String requesterId,
                                                     @PathVariable String targetSystemId,
                                                     @RequestBody RequesterRequests.ChangeTarget request,
                                                     @RequestAttribute(AdminApiKeyFilter.OPERATOR) String operator) {
        return RequesterViews.RequesterView.from(management.removeTarget(
                requesterId, targetSystemId, request.requiredExpectedVersion(), request.note(operator)));
    }

    @GetMapping("/{requesterId}/history")
    public List<RequesterViews.History> history(@PathVariable String requesterId) {
        return management.history(requesterId).stream()
                .map(RequesterViews.History::from)
                .toList();
    }
}
