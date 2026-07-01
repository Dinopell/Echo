package com.echo.controlplane.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JWT Token 响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthTokenResponse {

    private String accessToken;

    /** Token 类型，固定 Bearer */
    @Builder.Default
    private String tokenType = "Bearer";

    /** 有效期（秒） */
    private long expiresIn;
}
