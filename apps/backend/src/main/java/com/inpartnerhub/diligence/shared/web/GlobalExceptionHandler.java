package com.inpartnerhub.diligence.shared.web;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 9457 problem details. Unexpected errors never leak internal messages to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ResourceConflictException.class)
    ProblemDetail handleConflict(ResourceConflictException ex) {
        return problem(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalid(InvalidRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, ex.getMessage());
    }

    /** Unique constraints that the application could not pre-check (e.g. rows hidden by row-level security). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.info("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "The request conflicts with existing data.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Access denied.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleUnauthenticated(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, "Authentication required.");
    }

    @ExceptionHandler(UpstreamServiceException.class)
    ProblemDetail handleUpstream(UpstreamServiceException ex) {
        log.error("Upstream service failure", ex);
        return problem(HttpStatus.BAD_GATEWAY, ProblemTypes.UPSTREAM_FAILURE, "A dependent service is unavailable.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR, "An unexpected error occurred.");
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        return problem;
    }
}
