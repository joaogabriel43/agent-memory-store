package com.agentmemorystore.presentation.exception;

/**
 * Thrown when request parameters fail validation (e.g. a blank query or a non-positive limit).
 * Translated to an HTTP 400 Problem Details response by {@link GlobalExceptionHandler}.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
