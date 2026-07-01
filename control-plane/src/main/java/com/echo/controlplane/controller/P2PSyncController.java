package com.echo.controlplane.controller;

import com.echo.controlplane.dto.ApiResponse;
import com.echo.controlplane.service.P2PSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * P2P 局域网同步控制器
 *
 * <p>API 端点：
 * <pre>
 *   GET    /api/v1/sync/status           — 查询 P2P 同步状态
 *   POST   /api/v1/sync/trigger          — 触发手动同步（发送本地快照到对端）
 *   POST   /api/v1/sync/receive          — 接收对端发来的 .echo 快照
 *   GET    /api/v1/sync/discover         — 发现局域网内的 Echo 设备
 * </pre>
 *
 * <p>所有同步操作均在局域网内完成，不经过互联网。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
public class P2PSyncController {

    private final P2PSyncService p2pSyncService;

    /**
     * 查询 P2P 同步当前状态
     *
     * @return 同步状态信息（是否启用、已连接对端数等）
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSyncStatus() {
        return ResponseEntity.ok(ApiResponse.success(p2pSyncService.getSyncStatus()));
    }

    /**
     * 触发与指定设备的手动同步（向对端发送本地快照）
     *
     * @param request 包含 peerAddress（目标设备 http://IP:PORT）的请求体
     * @return 同步触发结果
     */
    @PostMapping("/trigger")
    public ResponseEntity<ApiResponse<String>> triggerSync(
            @RequestBody Map<String, String> request) {
        String peerAddress = request.get("peerAddress");
        if (peerAddress == null || peerAddress.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("peerAddress 不能为空，格式示例: 192.168.1.100:8080"));
        }
        String result = p2pSyncService.triggerSync(peerAddress);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 接收对端发来的 .echo 快照并合并到本地图谱
     *
     * <p>此接口由对端调用，控制面收到后：
     * <ol>
     *   <li>验证签名（SHA-256 防伪造）</li>
     *   <li>解析快照 JSON</li>
     *   <li>MERGE 人物节点和对话节点到本地 Neo4j</li>
     * </ol>
     *
     * @param request 包含 snapshot、signature、sourcePeer 的请求体
     * @return 合并结果（mergedPersons、mergedConversations 等）
     */
    @PostMapping("/receive")
    public ResponseEntity<ApiResponse<Map<String, Object>>> receiveSnapshot(
            @RequestBody Map<String, String> request) {
        String snapshot = request.get("snapshot");
        String signature = request.get("signature");
        String sourcePeer = request.getOrDefault("sourcePeer", "unknown");

        if (snapshot == null || snapshot.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("snapshot 不能为空"));
        }

        log.info("收到 P2P 快照同步请求: sourcePeer={}", sourcePeer);
        Map<String, Object> mergeResult = p2pSyncService.receiveSnapshot(snapshot, signature, sourcePeer);
        return ResponseEntity.ok(ApiResponse.success("快照合并完成", mergeResult));
    }

    /**
     * 发现局域网内的 Echo 设备
     *
     * @return 已发现的设备列表
     */
    @GetMapping("/discover")
    public ResponseEntity<ApiResponse<Map<String, Object>>> discoverPeers() {
        return ResponseEntity.ok(ApiResponse.success(p2pSyncService.discoverPeers()));
    }
}
