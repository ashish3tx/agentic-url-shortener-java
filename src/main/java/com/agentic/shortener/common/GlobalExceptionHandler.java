package com.agentic.shortener.common;

import com.agentic.shortener.orchestrator.error.IllegalRunStateException;
import com.agentic.shortener.orchestrator.error.OrchestratorException;
import com.agentic.shortener.orchestrator.error.PolicyViolationException;
import com.agentic.shortener.orchestrator.error.RunNotFoundException;
import com.agentic.shortener.url.error.AliasConflictException;
import com.agentic.shortener.url.error.IdempotencyConflictException;
import com.agentic.shortener.url.error.IdempotencyInProgressException;
import com.agentic.shortener.url.error.InvalidLinkException;
import com.agentic.shortener.url.error.LinkAccessDeniedException;
import com.agentic.shortener.url.error.LinkGoneException;
import com.agentic.shortener.url.error.LinkNotFoundException;
import com.agentic.shortener.url.error.RateLimitExceededException;
import com.agentic.shortener.url.error.ShortenerException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * RFC 7807 problem responses. Sealed exception hierarchies make the status mapping exhaustive.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ShortenerException.class)
    ResponseEntity<ProblemDetail> handleShortener(ShortenerException ex) {
        var status = switch (ex) {
            case InvalidLinkException e -> HttpStatus.BAD_REQUEST;
            case LinkNotFoundException e -> HttpStatus.NOT_FOUND;
            case LinkGoneException e -> HttpStatus.GONE;
            case AliasConflictException e -> HttpStatus.CONFLICT;
            case IdempotencyConflictException e -> HttpStatus.UNPROCESSABLE_CONTENT;
            case IdempotencyInProgressException e -> HttpStatus.CONFLICT;
            case LinkAccessDeniedException e -> HttpStatus.FORBIDDEN;
            case RateLimitExceededException e -> HttpStatus.TOO_MANY_REQUESTS;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        var response = ResponseEntity.status(status);
        switch (ex) {
            case InvalidLinkException invalid -> problem.setProperty("field", invalid.field());
            case RateLimitExceededException limited ->
                    response.header(HttpHeaders.RETRY_AFTER, String.valueOf(limited.retryAfterSeconds()));
            default -> {
                // no extra detail
            }
        }
        return response.body(problem);
    }

    @ExceptionHandler(OrchestratorException.class)
    ResponseEntity<ProblemDetail> handleOrchestrator(OrchestratorException ex) {
        var status = switch (ex) {
            case RunNotFoundException e -> HttpStatus.NOT_FOUND;
            case IllegalRunStateException e -> HttpStatus.CONFLICT;
            case PolicyViolationException e -> HttpStatus.FORBIDDEN;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        if (ex instanceof PolicyViolationException violation) {
            problem.setProperty("rule", violation.rule());
        }
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = ex.getBindingResult().getAllErrors().stream()
                .collect(Collectors.toMap(
                        error -> error instanceof FieldError f ? f.getField() : error.getObjectName(),
                        error -> String.valueOf(error.getDefaultMessage()),
                        (first, second) -> first));
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request validation failed");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }
}
