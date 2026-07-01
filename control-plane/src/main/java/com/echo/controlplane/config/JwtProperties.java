package com.echo.controlplane.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 认证配置（从环境变量注入）
 */
@Data
@Component
@ConfigurationProperties(prefix = "echo.security.jwt")
public class JwtProperties {

    /** 是否启用 JWT 认证（开发环境默认关闭） */
    private boolean enabled = false;

    /** HS256 签名密钥 */
    private String secret = "change_me_to_a_256bit_random_secret_string_for_local_use";

    /** Token 有效期（毫秒），默认 24 小时 */
    private long expirationMs = 86_400_000L;

    /** 用于换取 Token 的 API Key（与 JWT_SECRET 独立） */
    private String apiKey = "";
}
