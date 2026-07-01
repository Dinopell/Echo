package com.echo.controlplane.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 获取 JWT Token 的请求体
 */
@Data
public class AuthTokenRequest {

    /** 与 ECHO_API_KEY 环境变量匹配的 API Key */
    @NotBlank(message = "apiKey 不能为空")
    private String apiKey;
}
