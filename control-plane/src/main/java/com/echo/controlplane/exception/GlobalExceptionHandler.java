package com.echo.controlplane.exception;

import com.echo.controlplane.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * <p>统一捕获并处理以下异常类型：
 * <ul>
 *   <li>{@link EchoException} — 业务异常，按异常中的 HTTP 状态码返回</li>
 *   <li>{@link MethodArgumentNotValidException} — 参数校验失败（@Valid）</li>
 *   <li>{@link MissingServletRequestParameterException} — 缺少必填请求参数</li>
 *   <li>{@link MaxUploadSizeExceededException} — 文件超过上传大小限制</li>
 *   <li>{@link IllegalArgumentException} — 非法参数</li>
 *   <li>{@link Exception} — 未知异常兜底处理</li>
 * </ul>
 *
 * <p>所有异常均返回统一的 {@link ApiResponse} 格式。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理 Echo 业务异常
     *
     * @param ex 业务异常
     * @return 包含错误信息的响应体
     */
    @ExceptionHandler(EchoException.class)
    public ResponseEntity<ApiResponse<Void>> handleEchoException(EchoException ex) {
        log.warn("业务异常: code={}, message={}", ex.getErrorCode(), ex.getMessage());
        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .code(ex.getErrorCode())
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(ex.getHttpStatus()).body(response);
    }

    /**
     * 处理参数校验失败（@Valid / @Validated 注解触发）
     *
     * @param ex 参数校验异常
     * @return 400 响应，附带详细字段错误信息
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        // 收集所有字段的校验错误
        String errorDetails = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));

        log.warn("参数校验失败: {}", errorDetails);
        return ResponseEntity.badRequest()
                .body(ApiResponse.badRequest("参数校验失败: " + errorDetails));
    }

    /**
     * 处理缺少必填请求参数
     *
     * @param ex 缺少请求参数异常
     * @return 400 响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("缺少必填参数: {}", ex.getParameterName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.badRequest("缺少必填参数: " + ex.getParameterName()));
    }

    /**
     * 处理文件上传超出大小限制
     *
     * @param ex 文件过大异常
     * @return 400 响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleFileSizeExceeded(MaxUploadSizeExceededException ex) {
        log.warn("文件上传超出大小限制: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.badRequest("上传文件超出大小限制，请压缩后重试"));
    }

    /**
     * 处理 Multipart 上传异常
     *
     * @param ex Multipart 异常
     * @return 400 响应
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipartException(MultipartException ex) {
        log.warn("文件上传异常: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.badRequest("文件上传失败: " + ex.getMessage()));
    }

    /**
     * 处理非法参数异常
     *
     * @param ex 非法参数异常
     * @return 400 响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("非法参数: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.badRequest(ex.getMessage()));
    }

    /**
     * 兜底处理：捕获所有未预期异常
     *
     * <p>内部错误不对外暴露细节，防止信息泄露。
     *
     * @param ex 未知异常
     * @return 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknownException(Exception ex) {
        log.error("未预期的内部错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("服务内部错误，请稍后重试"));
    }
}
