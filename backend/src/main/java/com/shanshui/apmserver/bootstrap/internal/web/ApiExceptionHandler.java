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
import com.shanshui.apmserver.identity.api.QueryTokenLimitException;
import com.shanshui.apmserver.identity.api.QueryTokenNotFoundException;
import com.shanshui.apmserver.memory.api.MemoryLeakEventConflictException;
import com.shanshui.apmserver.memory.api.MemoryLeakReportValidationException;
import com.shanshui.apmserver.memory.api.MemoryLeakAttachmentStoreException;
import com.shanshui.apmserver.identity.api.UnauthenticatedException;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import com.shanshui.apmserver.platform.api.UnsupportedMediaTypeException;
import com.shanshui.apmserver.ingest.api.UnsupportedSchemaVersionException;
import com.shanshui.apmserver.jank.api.StackParserBusyException;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.symbol.api.SymbolConflictException;
import com.shanshui.apmserver.symbol.api.SymbolParserBusyException;
import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import com.shanshui.apmserver.symbol.api.SymbolValidationException;
import com.shanshui.apmserver.symbol.api.SymbolVersionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

    /** 框架字段错误只包含声明字段和固定说明，不携带 rejectedValue 或原始异常。 */
    private record RequestFieldError(String field, String code, String message) { }

    /** 正文解析/解码失败统一使用受控说明，未知 JSON 属性也不回显。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> unreadableBody(HttpMessageNotReadableException ex) {
        return response(HttpStatus.BAD_REQUEST,
                ApiErrorResponse.of("INVALID_REQUEST_BODY", "请求正文缺失或格式错误", false, null));
    }

    /** URL 和请求头的类型转换失败，不把提交值拼入消息。 */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> invalidParameterType(TypeMismatchException ex) {
        // MVC 参数名称来自声明；其余绑定异常无可安全定位字段时使用统一占位。
        String field = ex instanceof MethodArgumentTypeMismatchException argument ? argument.getName() : null;
        return frameworkError(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "请求参数格式错误",
                List.of(new RequestFieldError(safeField(field), "TYPE_MISMATCH", "参数类型错误")), null);
    }

    /** 只映射调用方缺失项；MissingPathVariable 等服务端声明错误不在此列。 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MissingRequestHeaderException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<ApiErrorResponse> missingParameter(Exception ex) {
        // 三种异常中的名字均来自控制器声明。
        String field = ex instanceof MissingServletRequestParameterException parameter ? parameter.getParameterName()
                : ex instanceof MissingRequestHeaderException header ? header.getHeaderName()
                : ((MissingServletRequestPartException) ex).getRequestPartName();
        return frameworkError(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "缺少必填请求项",
                List.of(new RequestFieldError(safeField(field), "REQUIRED", "必填请求项缺失")), null);
    }

    /** ModelAttribute 的类型绑定失败必须区别于已解码值的约束失败。 */
    @ExceptionHandler({BindException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiErrorResponse> bindingValidation(BindException ex) {
        // errors 有硬上限；消息不使用可包含敏感值的默认校验模板。
        BindingResult result = ex.getBindingResult();
        boolean typeMismatch = result.getFieldErrors().stream().anyMatch(error -> error.isBindingFailure());
        List<RequestFieldError> errors = result.getFieldErrors().stream().limit(50)
                .map(error -> new RequestFieldError(safeField(error.getField()),
                        error.isBindingFailure() ? "TYPE_MISMATCH" : "CONSTRAINT_VIOLATION",
                        error.isBindingFailure() ? "参数类型错误" : "字段不符合校验要求"))
                .toList();
        return frameworkError(HttpStatus.BAD_REQUEST, typeMismatch ? "INVALID_PARAMETER" : "VALIDATION_FAILED",
                typeMismatch ? "请求参数格式错误" : "请求字段校验失败", errors, null);
    }

    /** 方法输入约束返回 400，返回值约束属于内部错误，保留 500。 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> methodValidation(HandlerMethodValidationException ex) {
        // 不暴露返回值、跨参数表达式或约束模板。
        List<RequestFieldError> errors = ex.isForReturnValue() ? List.of()
                : ex.getParameterValidationResults().stream().limit(50)
                .map(result -> new RequestFieldError(safeField(result.getMethodParameter().getParameterName()),
                        "CONSTRAINT_VIOLATION", "参数不符合校验要求")).toList();
        return frameworkError(ex.getStatusCode(), ex.isForReturnValue() ? "INTERNAL_ERROR" : "VALIDATION_FAILED",
                ex.isForReturnValue() ? "服务内部校验失败" : "请求参数校验失败", errors, null);
    }

    /** 适用于方法输入的 Bean Validation；返回值违反约束不归为调用方错误。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> constraintValidation(ConstraintViolationException ex) {
        // 返回值校验路径节点可可靠区分服务器失败，不读取无效值。
        boolean returnValue = ex.getConstraintViolations().stream().anyMatch(violation -> {
            for (var node : violation.getPropertyPath()) {
                if (node.getKind() == jakarta.validation.ElementKind.RETURN_VALUE) return true;
            }
            return false;
        });
        return frameworkError(returnValue ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST,
                returnValue ? "INTERNAL_ERROR" : "VALIDATION_FAILED",
                returnValue ? "服务内部校验失败" : "请求参数校验失败", List.of(), null);
    }

    /** 405 保留框架 Allow 响应头。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return frameworkError(ex.getStatusCode(), "METHOD_NOT_ALLOWED", "请求方法不支持", List.of(), ex.getHeaders());
    }

    /** 框架 415 保留媒体协商响应头，不替换领域专项错误。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> frameworkMediaType(HttpMediaTypeNotSupportedException ex) {
        return frameworkError(ex.getStatusCode(), "UNSUPPORTED_MEDIA_TYPE", "请求媒体类型不支持", List.of(), ex.getHeaders());
    }

    /** 字段路径只允许受控标识；客户端值或集合键不能借路径回显。 */
    private String safeField(String field) {
        return field != null && field.length() <= 100 && field.matches("[A-Za-z][A-Za-z0-9_.-]*")
                ? field : "request";
    }

    /** 统一构建框架失败，沿用 requestId 可空约定。 */
    private ResponseEntity<ApiErrorResponse> frameworkError(HttpStatusCode status, String code, String message,
                                                           List<RequestFieldError> errors, HttpHeaders headers) {
        return ResponseEntity.status(status).headers(headers == null ? new HttpHeaders() : headers)
                .body(new ApiErrorResponse(code, message, false, null, errors.stream().limit(50).toList(), Instant.now()));
    }

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

    /** 将 Spring multipart 总请求限制转换为统一的 413 错误。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> multipartTooLarge(MaxUploadSizeExceededException ex) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE,
                ApiErrorResponse.of("PAYLOAD_TOO_LARGE", "上传文件超过大小上限", false, null));
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

    /** 返回符号表内容或请求校验错误。 */
    @ExceptionHandler(SymbolValidationException.class)
    public ResponseEntity<ApiErrorResponse> symbolValidation(SymbolValidationException ex) {
        return response(ResponseEntity.status(ex.getStatus())
                .body(ApiErrorResponse.of(ex.getCode(), ex.getMessage(), false, null)));
    }

    /** 返回同构建不同 mapping 的摘要冲突。 */
    @ExceptionHandler(SymbolConflictException.class)
    public ResponseEntity<ApiErrorResponse> symbolConflict(SymbolConflictException ex) {
        return response(ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("SYMBOL_CONFLICT", ex.getMessage(), false, null,
                        List.of(ex.getCurrent()), java.time.Instant.now())));
    }

    /** 返回基于旧 revision 的替换冲突。 */
    @ExceptionHandler(SymbolVersionConflictException.class)
    public ResponseEntity<ApiErrorResponse> symbolVersionConflict(SymbolVersionConflictException ex) {
        return response(ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse("SYMBOL_VERSION_CONFLICT", ex.getMessage(), false, null,
                        List.of(ex.getCurrent()), java.time.Instant.now())));
    }

    /** 返回符号表存储暂时不可用。 */
    @ExceptionHandler(SymbolStoreUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> symbolStoreUnavailable(SymbolStoreUnavailableException ex) {
        return response(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "30")
                .body(ApiErrorResponse.of("SYMBOL_STORE_UNAVAILABLE", ex.getMessage(), true, null)));
    }

    /** 返回符号表解析资源繁忙。 */
    @ExceptionHandler(SymbolParserBusyException.class)
    public ResponseEntity<ApiErrorResponse> symbolParserBusy(SymbolParserBusyException ex) {
        return response(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(ApiErrorResponse.of("SYMBOL_PARSER_BUSY", ex.getMessage(), true, null)));
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

    /** 应用范围内不存在该 Token，与跨应用 ID 保持相同响应。 */
    @ExceptionHandler(QueryTokenNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> queryTokenNotFound(QueryTokenNotFoundException ex) {
        return response(HttpStatus.NOT_FOUND,
                ApiErrorResponse.of("QUERY_TOKEN_NOT_FOUND", ex.getMessage(), false, null));
    }

    /** 有效 Token 数量达到配置上限时要求管理员先撤销旧凭据。 */
    @ExceptionHandler(QueryTokenLimitException.class)
    public ResponseEntity<ApiErrorResponse> queryTokenLimit(QueryTokenLimitException ex) {
        return response(HttpStatus.CONFLICT,
                ApiErrorResponse.of("QUERY_TOKEN_LIMIT", ex.getMessage(), false, null));
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

    @ExceptionHandler(MemoryLeakReportValidationException.class)
    public ResponseEntity<ApiErrorResponse> memoryLeakValidation(MemoryLeakReportValidationException ex) {
        return response(HttpStatus.BAD_REQUEST, ApiErrorResponse.of("INVALID_MEMORY_LEAK_REPORT", ex.getMessage(), false, null));
    }

    @ExceptionHandler(MemoryLeakEventConflictException.class)
    public ResponseEntity<ApiErrorResponse> memoryLeakConflict(MemoryLeakEventConflictException ex) {
        return response(HttpStatus.CONFLICT, ApiErrorResponse.of("EVENT_ID_CONFLICT", ex.getMessage(), false, null));
    }

    @ExceptionHandler(MemoryLeakAttachmentStoreException.class)
    public ResponseEntity<ApiErrorResponse> memoryLeakAttachmentFailure(MemoryLeakAttachmentStoreException ex) {
        return response(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "30")
                .body(ApiErrorResponse.of("ATTACHMENT_STORE_UNAVAILABLE", ex.getMessage(), true, null)));
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, ApiErrorResponse body) {
        return ResponseEntity.status(status).body(body);
    }

    private ResponseEntity<ApiErrorResponse> response(ResponseEntity<ApiErrorResponse> body) {
        return body;
    }
}
