package com.inpartnerhub.diligence.shared.web;

import java.net.URI;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
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

    /**
     * Tomcat 11 rejects malformed query strings (e.g. {@code ?=}, a parameter without a name) when parameters are
     * read, instead of ignoring them. It is the caller's error, not ours: 400, not 500 (found by the DAST API scan).
     */
    @ExceptionHandler(InvalidParameterException.class)
    ProblemDetail handleMalformedParameters(InvalidParameterException ex) {
        return problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, "The query string is malformed.");
    }

    /**
     * A multipart body that cannot be parsed (no boundary, not terminated) is the caller's error too: 400, not 500
     * (found by the DAST API scan with ZAP 2.17, #84). Too large uploads keep their own 413 from the base class.
     */
    @ExceptionHandler(MultipartException.class)
    ProblemDetail handleMalformedMultipart(MultipartException ex) {
        if (ex instanceof MaxUploadSizeExceededException) {
            return problem(HttpStatus.CONTENT_TOO_LARGE, ProblemTypes.VALIDATION, "The upload is too large.");
        }
        return problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, "The multipart request body is malformed.");
    }

    /**
     * A body without Content-Length that passed {@link RequestBodyLimitFilter}'s limit while it was read (#117):
     * the message converter reports it as unreadable, but it is too large, not malformed.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestBodyLimitFilter.BodyTooLargeException) {
                return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem(HttpStatus.CONTENT_TOO_LARGE,
                        ProblemTypes.VALIDATION, "The request body is too large."));
            }
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
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

    @ExceptionHandler(StepUpRequiredException.class)
    ProblemDetail handleStepUp(StepUpRequiredException ex) {
        var problem = problem(HttpStatus.FORBIDDEN, ProblemTypes.STEP_UP_REQUIRED, ex.getMessage());
        problem.setProperty("maxAgeSeconds", ex.maxAge().toSeconds());
        return problem;
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
