package com.hyuk.flow_control.adapter.in.web;

import com.hyuk.flow_control.domain.dryrun.DryRunAlreadyRunningException;
import com.hyuk.flow_control.domain.dryrun.DryRunNotFoundException;
import com.hyuk.flow_control.domain.policy.PolicyAlreadyExistsException;
import com.hyuk.flow_control.domain.policy.PolicyNotFoundException;
import com.hyuk.flow_control.domain.policy.PolicyVersionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {PolicyAdminController.class, DryRunAdminController.class})
public class PolicyAdminExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({PolicyNotFoundException.class, DryRunNotFoundException.class})
    public ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({PolicyAlreadyExistsException.class, PolicyVersionConflictException.class,
            DryRunAlreadyRunningException.class})
    public ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
