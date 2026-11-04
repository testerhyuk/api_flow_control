package com.hyuk.mock_target.fault;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/actuator/fault-timeline")
public class FaultTimelineController {
    private final FaultTimeline timeline;

    public FaultTimelineController(FaultTimeline timeline) {
        this.timeline = timeline;
    }

    @PostMapping
    public FaultTimelineStatus start(@RequestBody StartRequest request) {
        return timeline.start(new FaultTimelinePlan(request.faults()));
    }

    @GetMapping
    public FaultTimelineStatus status() {
        return timeline.status();
    }

    @DeleteMapping
    public FaultTimelineStatus stop() {
        return timeline.stop();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    public record StartRequest(List<FaultTimelinePlan.Entry> faults) {
    }
}
