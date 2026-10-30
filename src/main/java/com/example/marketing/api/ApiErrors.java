package com.example.marketing.api;

import com.example.marketing.domain.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    public record Failure(String code, String message) {}

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Failure> business(BusinessException error) {
        return ResponseEntity.status(error.status()).body(new Failure(error.code(), error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Failure> validation(MethodArgumentNotValidException error) {
        var field = error.getBindingResult().getFieldErrors().stream().findFirst();
        return ResponseEntity.badRequest().body(new Failure("INVALID_REQUEST",
                field.map(value -> value.getField() + ": " + value.getDefaultMessage()).orElse("Invalid request")));
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Failure> invalidRequest(Exception error) {
        return ResponseEntity.badRequest().body(new Failure("INVALID_REQUEST", "Invalid request fields or JSON"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Failure> database(DataAccessException error) {
        log.error("Database operation failed", error);
        return ResponseEntity.status(503).body(new Failure("STORAGE_UNAVAILABLE", "Storage operation failed; retry with the same idempotency key"));
    }
}
