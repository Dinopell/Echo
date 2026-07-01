package com.echo.controlplane.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Echo 业务异常
 *
 * <p>用于封装所有业务层抛出的可预期异常，携带 HTTP 状态码和错误描述。
 * 由 {@link GlobalExceptionHandler} 统一捕获并转换为标准 ApiResponse。
 *
 * <p>使用示例：
 * <pre>
 *   throw new EchoException("音频文件格式不支持", HttpStatus.BAD_REQUEST);
 *   throw new EchoException(EchoException.ErrorCode.TASK_NOT_FOUND, taskId);
 * </pre>
 */
@Getter
public class EchoException extends RuntimeException {

    /** 对应的 HTTP 状态码 */
    private final HttpStatus httpStatus;

    /** 业务错误码（可选，用于前端精确处理） */
    private final int errorCode;

    /**
     * 构造业务异常（使用 HTTP 状态码）
     *
     * @param message    错误描述
     * @param httpStatus 对应的 HTTP 状态码
     */
    public EchoException(String message, HttpStatus httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = httpStatus.value();
    }

    /**
     * 构造业务异常（使用预定义错误码）
     *
     * @param code 预定义错误码
     */
    public EchoException(ErrorCode code) {
        super(code.getMessage());
        this.httpStatus = code.getHttpStatus();
        this.errorCode = code.getCode();
    }

    /**
     * 构造业务异常（预定义错误码 + 额外上下文）
     *
     * @param code    预定义错误码
     * @param context 额外上下文信息（拼接到消息后）
     */
    public EchoException(ErrorCode code, String context) {
        super(code.getMessage() + ": " + context);
        this.httpStatus = code.getHttpStatus();
        this.errorCode = code.getCode();
    }

    /**
     * 构造业务异常（带原始异常）
     *
     * @param message    错误描述
     * @param httpStatus 对应的 HTTP 状态码
     * @param cause      原始异常
     */
    public EchoException(String message, HttpStatus httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.errorCode = httpStatus.value();
    }

    // ── 预定义错误码枚举 ─────────────────────────────────────

    /**
     * 预定义的业务错误码
     */
    @Getter
    public enum ErrorCode {

        // 音频相关 (1xxx)
        AUDIO_FILE_EMPTY(1001, "音频文件不能为空", HttpStatus.BAD_REQUEST),
        AUDIO_FORMAT_NOT_SUPPORTED(1002, "音频格式不支持，仅支持 wav/mp3/m4a", HttpStatus.BAD_REQUEST),
        AUDIO_UPLOAD_FAILED(1003, "音频文件上传至存储失败", HttpStatus.INTERNAL_SERVER_ERROR),
        AUDIO_ENCRYPT_FAILED(1004, "音频文件加密失败", HttpStatus.INTERNAL_SERVER_ERROR),
        AUDIO_URL_GENERATE_FAILED(1005, "生成预签名 URL 失败", HttpStatus.INTERNAL_SERVER_ERROR),

        // 任务相关 (2xxx)
        TASK_ENQUEUE_FAILED(2001, "任务入队失败", HttpStatus.INTERNAL_SERVER_ERROR),
        TASK_NOT_FOUND(2002, "任务不存在", HttpStatus.NOT_FOUND),
        TASK_STATUS_ERROR(2003, "任务状态查询失败", HttpStatus.INTERNAL_SERVER_ERROR),

        // 图谱相关 (3xxx)
        PERSON_NOT_FOUND(3001, "人物节点不存在", HttpStatus.NOT_FOUND),
        GRAPH_QUERY_FAILED(3002, "图谱查询失败", HttpStatus.INTERNAL_SERVER_ERROR),
        GRAPH_UPDATE_FAILED(3003, "图谱更新失败", HttpStatus.INTERNAL_SERVER_ERROR),
        CONVERSATION_NOT_FOUND(3004, "对话节点不存在", HttpStatus.NOT_FOUND),

        // P2P 同步相关 (4xxx)
        P2P_DISABLED(4001, "P2P 同步功能未启用", HttpStatus.SERVICE_UNAVAILABLE),
        P2P_SYNC_FAILED(4002, "P2P 同步失败", HttpStatus.INTERNAL_SERVER_ERROR),
        P2P_SIGNATURE_INVALID(4003, "P2P 快照签名验证失败", HttpStatus.UNAUTHORIZED),
        P2P_DECRYPT_FAILED(4004, "P2P 快照解密失败", HttpStatus.BAD_REQUEST),

        // 回调相关 (5xxx)
        CALLBACK_INVALID_PAYLOAD(5001, "回调数据格式错误", HttpStatus.BAD_REQUEST),
        CALLBACK_PROCESS_FAILED(5002, "回调处理失败", HttpStatus.INTERNAL_SERVER_ERROR),

        // 认证相关 (6xxx)
        AUTH_INVALID_API_KEY(6001, "API Key 无效", HttpStatus.UNAUTHORIZED),
        AUTH_TOKEN_INVALID(6002, "Token 无效或已过期", HttpStatus.UNAUTHORIZED),

        // 数据面调用相关 (7xxx)
        DATA_PLANE_UNAVAILABLE(7001, "数据面服务不可用", HttpStatus.SERVICE_UNAVAILABLE),
        DATA_PLANE_EMBED_FAILED(7002, "向量嵌入调用失败", HttpStatus.INTERNAL_SERVER_ERROR),

        // 语义搜索相关 (8xxx)
        SEMANTIC_SEARCH_FAILED(8001, "语义搜索失败", HttpStatus.INTERNAL_SERVER_ERROR);

        /** 业务错误码 */
        private final int code;

        /** 错误描述 */
        private final String message;

        /** 对应 HTTP 状态码 */
        private final HttpStatus httpStatus;

        ErrorCode(int code, String message, HttpStatus httpStatus) {
            this.code = code;
            this.message = message;
            this.httpStatus = httpStatus;
        }
    }
}
