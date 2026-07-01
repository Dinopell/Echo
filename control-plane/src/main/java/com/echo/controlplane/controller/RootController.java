package com.echo.controlplane.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 根路径说明页（纯 API 服务，无 Web 前端）
 */
@RestController
public class RootController {

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> index() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "Echo Control Plane");
        body.put("message", "这是 API 服务，请访问下方链接或使用 curl / Postman 调用接口");
        body.put("health", "/actuator/health");
        body.put("authStatus", "/api/v1/auth/status");
        body.put("conversations", "/api/v1/graph/conversations");
        body.put("uploadAudio", "POST /api/v1/audio/upload");
        body.put("dataPlaneDocs", "http://localhost:8001/docs");
        body.put("neo4jBrowser", "http://localhost:7474");
        body.put("minioConsole", "http://localhost:9001");
        return ResponseEntity.ok(body);
    }
}
