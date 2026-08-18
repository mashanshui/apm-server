package com.shanshui.apmserver.web;

import com.shanshui.apmserver.domain.ApiErrorResponse;
import com.shanshui.apmserver.domain.EventError;
import com.shanshui.apmserver.service.EventValidationException;
import com.shanshui.apmserver.service.InvalidBatchException;
import com.shanshui.apmserver.service.InvalidProjectKeyException;
import com.shanshui.apmserver.service.PayloadTooLargeException;
import com.shanshui.apmserver.service.ProjectAccessDeniedException;
import com.shanshui.apmserver.service.QueryValidationException;
import com.shanshui.apmserver.service.UnsupportedMediaTypeException;
import com.shanshui.apmserver.service.UnsupportedSchemaVersionException;
import com.shanshui.apmserver.repository.EventStoreUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidProjectKeyException.class)
    public ResponseEntity<ApiErrorResponse> invalidProjectKey(InvalidProjectKeyException ex) {
        return response(HttpStatus.UNAUTHORIZED, ApiErrorResponse.of("INVALID_PROJECT_KEY", ex.getMessage(), false, null));
    }

    @ExceptionHandler(InvalidBatchException.class)
    public ResponseEntity<ApiErrorResponse> invalidBatch(InvalidBatchException ex) {
        return response(HttpStatus.BAD_REQUEST, ApiErrorResponse.of("INVALID_BATCH", ex.getMessage(), false, null));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ApiErrorResponse> payloadTooLarge(PayloadTooLargeException ex) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, ApiErrorResponse.of("PAYLOAD_TOO_LARGE", ex.getMessage(), false, null));
    }

    @ExceptionHandler(UnsupportedMediaTypeException.class)
    public ResponseEntity<ApiErrorResponse> unsupportedMediaType(UnsupportedMediaTypeException ex) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ApiErrorResponse.of("UNSUPPORTED_MEDIA_TYPE", ex.getMessage(), false, null));
    }

    @ExceptionHandler(UnsupportedSchemaVersionException.class)
    public ResponseEntity<ApiErrorResponse> unsupportedSchema(UnsupportedSchemaVersionException ex) {
        return response(HttpStatus.BAD_REQUEST, ApiErrorResponse.of("UNSUPPORTED_SCHEMA_VERSION", ex.getMessage(), false, null));
    }

    @ExceptionHandler(EventStoreUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> eventStoreUnavailable(EventStoreUnavailableException ex) {
        return response(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "30")
                .body(ApiErrorResponse.of("EVENT_STORE_UNAVAILABLE", ex.getMessage(), true, null)));
    }

    @ExceptionHandler(EventValidationException.class)
    public ResponseEntity<ApiErrorResponse> eventValidation(EventValidationException ex) {
        List<EventError> errors = ex.getIssues().stream()
                .map(issue -> new EventError(null, null, issue.code(), issue.message(), false))
                .toList();
        return response(HttpStatus.BAD_REQUEST,
                new ApiErrorResponse("INVALID_EVENT", "事件校验失败", false, UUID.randomUUID().toString(), errors, java.time.Instant.now()));
    }

    @ExceptionHandler(ProjectAccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> projectAccessDenied(ProjectAccessDeniedException ex) {
        return response(HttpStatus.NOT_FOUND, ApiErrorResponse.of("PROJECT_NOT_FOUND", ex.getMessage(), false, null));
    }

    @ExceptionHandler(QueryValidationException.class)
    public ResponseEntity<ApiErrorResponse> queryValidation(QueryValidationException ex) {
        return response(ResponseEntity.status(ex.getStatus())
                .body(ApiErrorResponse.of(ex.getCode(), ex.getMessage(), false, null)));
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, ApiErrorResponse body) {
        return ResponseEntity.status(status).body(body);
    }

    private ResponseEntity<ApiErrorResponse> response(ResponseEntity<ApiErrorResponse> body) {
        return body;
    }
}
