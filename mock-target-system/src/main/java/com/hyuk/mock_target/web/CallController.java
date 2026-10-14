package com.hyuk.mock_target.web;

import com.hyuk.mock_target.config.MockTargetProperties;
import com.hyuk.mock_target.metrics.SimulatorMetrics;
import com.hyuk.mock_target.simulation.CallResult;
import com.hyuk.mock_target.simulation.CallSimulator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class CallController {
    private static final String REQUEST_ID_HEADER = "x-request-id";

    private final CallSimulator simulator;
    private final ResponseRenderer renderer;
    private final SimulatorMetrics metrics;
    private final MockTargetProperties.TargetSystem spec;

    public CallController(CallSimulator simulator, ResponseRenderer renderer,
                          SimulatorMetrics metrics, MockTargetProperties properties) {
        this.simulator = simulator;
        this.renderer = renderer;
        this.metrics = metrics;
        this.spec = properties.selected();
    }

    @PostMapping("/call")
    public ResponseEntity<String> call(@RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
                                       @RequestBody(required = false) String payload) throws InterruptedException {
        long startedAt = System.nanoTime();

        CallResult result = simulator.handle(requestId, payload);

        metrics.recordCall(result.outcome(), Duration.ofNanos(System.nanoTime() - startedAt));
        return renderer.render(result, requestId, spec.responseFormat());
    }
}
