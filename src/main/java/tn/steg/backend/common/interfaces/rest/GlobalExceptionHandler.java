package tn.steg.backend.common.interfaces.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.RateLimitExceededException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.application.dto.ErrorEnvelope;
import tn.steg.backend.common.application.dto.FieldErrorDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Centralized exception handling producing a uniform error envelope across all REST endpoints.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_KEY = "traceId";
    private static final String REDACTED = "[REDACTED]";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldErrorDto> fieldErrors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(FieldErrorDto.builder()
                    .field(fe.getField())
                    .rejectedValue(fe.getRejectedValue())
                    .message(fe.getDefaultMessage())
                    .build());
        }

        ErrorEnvelope envelope = buildEnvelope(
                HttpStatus.BAD_REQUEST,
                "Validation Failure",
                "Input payload failed validation constraints.",
                request,
                fieldErrors
        );
        // A13 security: rejected values may echo credentials/secrets. The client's
        // own input is returned in the envelope, but the LOG must not persist
        // rejected values (see steg.security PII/secret logging policy).
        log.warn("Validation error on path {}: {}", request.getRequestURI(), redact(fieldErrors));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(envelope);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldErrorDto> fieldErrors = new ArrayList<>();
        ex.getConstraintViolations().forEach(violation -> fieldErrors.add(FieldErrorDto.builder()
                .field(violation.getPropertyPath().toString())
                .rejectedValue(violation.getInvalidValue())
                .message(violation.getMessage())
                .build()));

        ErrorEnvelope envelope = buildEnvelope(
                HttpStatus.BAD_REQUEST,
                "Constraint Violation",
                "Request parameter violated validation constraints.",
                request,
                fieldErrors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(envelope);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorEnvelope> handleBusinessRuleException(BusinessRuleException ex, HttpServletRequest request) {
        ErrorEnvelope envelope = buildEnvelope(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getErrorCode(),
                ex.getMessage(),
                request,
                null
        );
        log.warn("Business rule violation on path {}: {} - {}", request.getRequestURI(), ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(envelope);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleResourceNotFoundException(ResourceNotFoundException ex, HttpServletRequest request) {
        ErrorEnvelope envelope = buildEnvelope(
                HttpStatus.NOT_FOUND,
                "Resource Not Found",
                ex.getMessage(),
                request,
                null
        );
        log.warn("Resource not found on path {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(envelope);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorEnvelope> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest request) {
        ErrorEnvelope envelope = buildEnvelope(
                HttpStatus.TOO_MANY_REQUESTS,
                "Rate Limit Exceeded",
                ex.getMessage(),
                request,
                null
        );
        log.warn("Rate limit exceeded on path {} (bucket={}, limit={}/{}s): {}", request.getRequestURI(),
                ex.getBucketName(), ex.getLimit(), ex.getWindowSeconds(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getWindowSeconds()))
                .body(envelope);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorEnvelope> handleAccessDeniedException(AccessDeniedException ex, HttpServletRequest request) {
        ErrorEnvelope envelope = buildEnvelope(
                HttpStatus.FORBIDDEN,
                "Access Denied",
                "You do not possess sufficient permissions to perform this action.",
                request,
                null
        );
        log.warn("Access denied on path {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(envelope);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorEnvelope> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        ErrorEnvelope envelope = buildEnvelope(
                HttpStatus.UNAUTHORIZED,
                "Authentication Required",
                "Full authentication is required to access this resource.",
                request,
                null
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(envelope);
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorEnvelope> handleDataIntegrityViolationException(
            org.springframework.dao.DataIntegrityViolationException ex, HttpServletRequest request) {
        // Backstop for residual unique-constraint races (e.g. count-based reference
        // allocation under concurrency): a safe 409 with no internals leaked.
        // Primary paths pre-check and return precise codes (e.g. CERTIFICATE_ALREADY_EXISTS).
        ErrorEnvelope envelope = buildEnvelope(
                HttpStatus.CONFLICT,
                "Concurrent Modification Conflict",
                "The request conflicts with a concurrent change (duplicate reference or record). Please refresh and retry.",
                request,
                null
        );
        log.warn("Data integrity conflict on path {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(envelope);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorEnvelope> handleOptimisticLockException(OptimisticLockingFailureException ex, HttpServletRequest request) {
        ErrorEnvelope envelope = buildEnvelope(
                HttpStatus.CONFLICT,
                "Concurrent Modification Conflict",
                "The target resource was modified concurrently by another transaction. Please refresh and retry.",
                request,
                null
        );
        log.warn("Optimistic locking conflict on path {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(envelope);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleGenericException(Exception ex, HttpServletRequest request) {
        ErrorEnvelope envelope = buildEnvelope(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected internal error occurred. Our engineering team has been notified.",
                request,
                null
        );
        log.error("Unhandled exception processing request to path {}: ", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(envelope);
    }

    private List<FieldErrorDto> redact(List<FieldErrorDto> fieldErrors) {
        if (fieldErrors == null) {
            return List.of();
        }
        return fieldErrors.stream()
                .map(e -> FieldErrorDto.builder()
                        .field(e.getField())
                        .rejectedValue(REDACTED)
                        .message(e.getMessage())
                        .build())
                .toList();
    }

    private ErrorEnvelope buildEnvelope(HttpStatus status, String error, String message, HttpServletRequest request, List<FieldErrorDto> fieldErrors) {
        String traceId = MDC.get(MDC_TRACE_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader(TRACE_ID_HEADER);
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        return ErrorEnvelope.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(error)
                .message(message)
                .path(request != null ? request.getRequestURI() : null)
                .traceId(traceId)
                .fieldErrors(fieldErrors != null ? fieldErrors : new ArrayList<>())
                .build();
    }
}
