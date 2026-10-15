package com.hyuk.record_ingestor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/generator")
public class GeneratorController {
    private final RequestGenerator generator;

    public GeneratorController(RequestGenerator generator) {
        this.generator = generator;
    }

    @GetMapping
    public GeneratorStatus status() {
        return generator.status();
    }

    @PostMapping("/start")
    public GeneratorStatus start(@RequestBody StartRequest request) {
        if (request.seed() == null) {
            throw new IllegalArgumentException("seed 는 필수입니다");
        }
        return generator.start(request.seed(), request.rates());
    }

    @PutMapping("/rates/{targetSystemId}")
    public GeneratorStatus changeRate(@PathVariable String targetSystemId, @RequestBody RateRequest request) {
        if (request.rate() == null) {
            throw new IllegalArgumentException("rate 는 필수입니다");
        }
        return generator.changeRate(targetSystemId, request.rate());
    }

    @PostMapping("/stop")
    public GeneratorStatus stop() {
        return generator.stopGenerating();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ProblemDetail invalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    public record StartRequest(Long seed, Map<String, Double> rates) {
    }

    public record RateRequest(Double rate) {
    }
}
