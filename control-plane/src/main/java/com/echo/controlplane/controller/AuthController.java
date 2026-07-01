package com.echo.controlplane.controller;

import com.echo.controlplane.config.JwtProperties;
import com.echo.controlplane.dto.ApiResponse;
import com.echo.controlplane.dto.AuthTokenRequest;
import com.echo.controlplane.dto.AuthTokenResponse;
import com.echo.controlplane.exception.EchoException;
import com.echo.controlplane.security.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器 — 本地 JWT 签发
 *
 * <p>API 端点：
 * <pre>
 *   POST /api/v1/auth/token  — 使用 API Key 换取 JWT
 *   GET  /api/v1/auth/status — 查询认证是否启用
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtProperties jwtProperties;
    private final JwtService jwtService;

    /**
     * 使用 API Key 换取 JWT Bearer Token
     */
    @PostMapping("/token")
    public ResponseEntity<ApiResponse<AuthTokenResponse>> issueToken(
            @Valid @RequestBody AuthTokenRequest request) {

        if (!jwtProperties.isEnabled()) {
            return ResponseEntity.ok(ApiResponse.success(
                    "JWT 认证未启用，无需 Token 即可访问 API",
                    AuthTokenResponse.builder()
                            .accessToken("")
                            .expiresIn(0)
                            .build()));
        }

        if (!jwtService.isApiKeyValid(request.getApiKey())) {
            throw new EchoException(EchoException.ErrorCode.AUTH_INVALID_API_KEY);
        }

        String token = jwtService.generateToken();
        long expiresInSeconds = jwtService.getExpirationMs() / 1000;

        return ResponseEntity.ok(ApiResponse.success("Token 签发成功",
                AuthTokenResponse.builder()
                        .accessToken(token)
                        .expiresIn(expiresInSeconds)
                        .build()));
    }

    /**
     * 查询 JWT 认证配置状态
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> authStatus() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "enabled", jwtProperties.isEnabled(),
                "expiresInSeconds", jwtProperties.getExpirationMs() / 1000
        )));
    }
}
