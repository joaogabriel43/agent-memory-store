package com.agentmemorystore.presentation.exception;

import com.agentmemorystore.domain.exception.EmbeddingUnavailableException;
import com.agentmemorystore.domain.exception.MemoryNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;

/**
 * Global exception handler that translates domain exceptions into clean HTTP responses.
 * Uses RFC 7807 Problem Details format.
 * <p>
 * Critical: Never exposes provider API keys, internal stack traces, or infrastructure details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MemoryNotFoundException.class)
    public ProblemDetail handleMemoryNotFound(MemoryNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Memory Not Found");
        problem.setType(URI.create("https://agent-memory-store/errors/memory-not-found"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(EmbeddingUnavailableException.class)
    public ProblemDetail handleEmbeddingUnavailable(EmbeddingUnavailableException ex) {
        log.error("Embedding service failure: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The embedding service is temporarily unavailable. Please try again later."
        );
        problem.setTitle("Embedding Service Unavailable");
        problem.setType(URI.create("https://agent-memory-store/errors/embedding-unavailable"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Required header '" + ex.getHeaderName() + "' is missing"
        );
        problem.setTitle("Missing Required Header");
        problem.setType(URI.create("https://agent-memory-store/errors/missing-header"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation Error");
        problem.setType(URI.create("https://agent-memory-store/errors/validation"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Invalid value for parameter '" + ex.getName() + "': " + ex.getValue()
        );
        problem.setTitle("Invalid Parameter");
        problem.setType(URI.create("https://agent-memory-store/errors/invalid-parameter"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Required parameter '" + ex.getParameterName() + "' is missing"
        );
        problem.setTitle("Missing Required Parameter");
        problem.setType(URI.create("https://agent-memory-store/errors/missing-parameter"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Handles explicit request-parameter validation failures
     * (e.g. a non-positive {@code limit} or a blank {@code query}).
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Validation Error");
        problem.setType(URI.create("https://agent-memory-store/errors/validation"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
