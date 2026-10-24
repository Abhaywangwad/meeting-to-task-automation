package com.meet2action.backend.common;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to a uniform JSON body: {status, code, error, message, timestamp, ...extras}.
 * Stack traces, SQL and credentials are logged server-side only and never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.getStatus(), e.getCode(), e.getMessage(), e.getExtra());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "validation_error", "Invalid request: " + detail, Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof UnrecognizedPropertyException u) {
            return body(HttpStatus.BAD_REQUEST, "unknown_field",
                    "Unknown or non-editable field: " + u.getPropertyName(), Map.of());
        }
        return body(HttpStatus.BAD_REQUEST, "malformed_request", "Malformed or invalid request body", Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_parameter", "Invalid value for parameter: " + e.getName(), Map.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> optimisticLock(ObjectOptimisticLockingFailureException e) {
        return body(HttpStatus.CONFLICT, "version_conflict",
                "The task was modified concurrently; reload it and retry", Map.of());
    }

    /** DataAccessException covers query failures; TransactionException covers "cannot open a connection". */
    @ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class})
    public ResponseEntity<Map<String, Object>> database(Exception e) {
        log.error("Database error: {}", e.getClass().getSimpleName(), e);
        return body(HttpStatus.SERVICE_UNAVAILABLE, "database_error",
                "The database is unavailable or the operation failed", Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", "Resource not found", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal server error", Map.of());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message,
                                                     Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status.value());
        out.put("code", code);
        out.put("error", message);
        out.put("message", message);
        out.put("timestamp", Instant.now().toString());
        out.putAll(extra);
        return ResponseEntity.status(status).body(out);
    }
}
