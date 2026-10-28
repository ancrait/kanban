package com.kanban.backend.infrastructure.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiValidationErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(
                new ApiValidationErrorResponse(400, "Validation Failed", "VALIDATION_FAILED", errors, Instant.now()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(
                new ApiErrorResponse(400, "Bad Request", "INVALID_REQUEST",
                        "Некоректний JSON, поля або параметри запиту.", Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(Exception exception) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        HttpHeaders headers = HttpHeaders.EMPTY;
        if (exception instanceof ErrorResponse error) {
            status = HttpStatus.valueOf(error.getStatusCode().value());
            headers = error.getHeaders();
        }

        String code = switch (status) {
            case BAD_REQUEST -> "INVALID_REQUEST";
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : status.name();
        };
        String message = "Запит не вдалося виконати.";
        if (status.is5xxServerError()) {
            message = "Сталася внутрішня помилка сервера.";
            // Exception messages may contain passwords or tokens.
            log.error("API request failed: exceptionType={}", exception.getClass().getName());
        }

        return ResponseEntity.status(status).headers(headers).cacheControl(CacheControl.noStore()).body(
                new ApiErrorResponse(status.value(), status.getReasonPhrase(), code, message, Instant.now()));
    }
}