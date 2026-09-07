package com.shanshui.apmserver.bootstrap.internal.web;

import com.shanshui.apmserver.platform.api.ApiErrorResponse;
import com.shanshui.apmserver.ingest.api.EventError;
import com.shanshui.apmserver.telemetry.api.EventValidationException;
import com.shanshui.apmserver.ingest.api.InvalidBatchException;
import com.shanshui.apmserver.identity.api.InvalidAppKeyException;
import com.shanshui.apmserver.jank.api.InvalidStackArtifactException;
import com.shanshui.apmserver.jank.api.InvalidStackArtifactRequestException;
import com.shanshui.apmserver.platform.api.PayloadTooLargeException;
import com.shanshui.apmserver.identity.api.AppNotFoundException;
import com.shanshui.apmserver.identity.api.PackageNameConflictException;
import com.shanshui.apmserver.identity.api.AppRoleDeniedException;
import com.shanshui.apmserver.identity.api.AppCredentialCreationException;
import com.shanshui.apmserver.identity.api.AppCredentialDecryptionException;
import com.shanshui.apmserver.identity.api.AppAuthenticationUnavailableException;
import com.shanshui.apmserver.identity.api.PackageNameMismatchException;
import com.shanshui.apmserver.identity.api.InvalidCredentialsException;
import com.shanshui.apmserver.identity.api.InvalidAppInputException;
import com.shanshui.apmserver.identity.api.UnauthenticatedException;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import com.shanshui.apmserver.platform.api.UnsupportedMediaTypeException;
import com.shanshui.apmserver.ingest.api.UnsupportedSchemaVersionException;
import com.shanshui.apmserver.jank.api.StackParserBusyException;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidAppKeyException.class)
    public ResponseEntity<ApiErrorResponse> invalidAppKey(InvalidAppKeyException ex) {
        return response(HttpStatus.UNAUTHORIZED, ApiErrorResponse.of("INVALID_APP_KEY", ex.getMessage(), false, null));
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

    @ExceptionHandler(InvalidStackArtifactRequestException.class)
    public ResponseEntity<ApiErrorResponse> invalidStackArtifactRequest(InvalidStackArtifactRequestException ex) {
        return response(HttpStatus.BAD_REQUEST,
                ApiErrorResponse.of(ex.getCode(), ex.getMessage(), false, null));
    }

    @ExceptionHandler(InvalidStackArtifactException.class)
    public ResponseEntity<ApiErrorResponse> invalidStackArtifact(InvalidStackArtifactException ex) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorResponse.of(ex.getCode(), ex.getMessage(), false, null));
    }

    @ExceptionHandler(StackParserBusyException.class)
    public ResponseEntity<ApiErrorResponse> stackParserBusy(StackParserBusyException ex) {
        return response(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(ApiErrorResponse.of("STACK_PARSER_BUSY", ex.getMessage(), true, null)));
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

    @ExceptionHandler(AppNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> appAccessDenied(AppNotFoundException ex) {
        return response(HttpStatus.NOT_FOUND, ApiErrorResponse.of("APP_NOT_FOUND", ex.getMessage(), false, null));
    }

    @ExceptionHandler(AppRoleDeniedException.class)
    public ResponseEntity<ApiErrorResponse> appRoleDenied(AppRoleDeniedException ex) {
        return response(HttpStatus.FORBIDDEN, ApiErrorResponse.of("FORBIDDEN", ex.getMessage(), false, null));
    }

    @ExceptionHandler(PackageNameConflictException.class)
    public ResponseEntity<ApiErrorResponse> packageNameConflict(PackageNameConflictException ex) {
        return response(HttpStatus.CONFLICT, ApiErrorResponse.of("PACKAGE_NAME_CONFLICT", ex.getMessage(), false, null));
    }

    @ExceptionHandler({AppCredentialCreationException.class, AppCredentialDecryptionException.class})
    public ResponseEntity<ApiErrorResponse> appCredentialUnavailable(RuntimeException ex) {
        return response(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "30")
                .body(ApiErrorResponse.of("APP_AUTH_UNAVAILABLE", ex.getMessage(), true, null)));
    }

    @ExceptionHandler(AppAuthenticationUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> appAuthenticationUnavailable(AppAuthenticationUnavailableException ex) {
        return response(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "30")
                .body(ApiErrorResponse.of("APP_AUTH_UNAVAILABLE", ex.getMessage(), true, null)));
    }

    @ExceptionHandler(PackageNameMismatchException.class)
    public ResponseEntity<ApiErrorResponse> packageNameMismatch(PackageNameMismatchException ex) {
        return response(HttpStatus.FORBIDDEN,
                ApiErrorResponse.of("PACKAGE_NAME_MISMATCH", ex.getMessage(), false, null));
    }

    @ExceptionHandler(InvalidAppInputException.class)
    public ResponseEntity<ApiErrorResponse> invalidAppInput(InvalidAppInputException ex) {
        return response(HttpStatus.BAD_REQUEST, ApiErrorResponse.of(ex.getCode(), ex.getMessage(), false, null));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> invalidCredentials(InvalidCredentialsException ex) {
        return response(HttpStatus.UNAUTHORIZED, ApiErrorResponse.of("INVALID_CREDENTIALS", ex.getMessage(), false, null));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ApiErrorResponse> unauthenticated(UnauthenticatedException ex) {
        return response(HttpStatus.UNAUTHORIZED, ApiErrorResponse.of("AUTH_REQUIRED", ex.getMessage(), false, null));
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
