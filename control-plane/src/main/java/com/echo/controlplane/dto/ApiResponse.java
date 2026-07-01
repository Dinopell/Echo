package com.echo.controlplane.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应封装
 *
 * <p>所有接口的响应体均使用此结构，格式如下：
 * <pre>
 * {
 *   "code": 200,
 *   "message": "操作成功",
 *   "data": { ... }
 * }
 * </pre>
 *
 * @param <T> 业务数据类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** 业务状态码（200=成功，400=参数错误，500=服务内部错误） */
    private int code;

    /** 提示消息 */
    private String message;

    /** 业务数据（失败时可为 null） */
    private T data;

    // ── 静态工厂方法 ─────────────────────────────────────────

    /**
     * 操作成功（携带数据）
     *
     * @param data 业务数据
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message("操作成功")
                .data(data)
                .build();
    }

    /**
     * 操作成功（自定义消息）
     *
     * @param message 提示消息
     * @param data    业务数据
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message(message)
                .data(data)
                .build();
    }

    /**
     * 操作成功（无数据）
     *
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success() {
        return ApiResponse.<T>builder()
                .code(200)
                .message("操作成功")
                .build();
    }

    /**
     * 参数校验失败
     *
     * @param message 错误描述
     * @return 400 响应
     */
    public static <T> ApiResponse<T> badRequest(String message) {
        return ApiResponse.<T>builder()
                .code(400)
                .message(message)
                .build();
    }

    /**
     * 服务内部错误
     *
     * @param message 错误描述
     * @return 500 响应
     */
    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .code(500)
                .message(message)
                .build();
    }

    /**
     * 自定义状态码
     *
     * @param code    状态码
     * @param message 提示消息
     * @return 响应
     */
    public static <T> ApiResponse<T> of(int code, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .build();
    }
}
