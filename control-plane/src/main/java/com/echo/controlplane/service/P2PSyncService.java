package com.echo.controlplane.service;

import com.echo.controlplane.exception.EchoException;
import com.echo.controlplane.model.ConversationNode;
import com.echo.controlplane.model.PersonNode;
import com.echo.controlplane.repository.ConversationRepository;
import com.echo.controlplane.repository.PersonRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P2P 局域网同步服务
 *
 * <p>负责在同一局域网内的多设备之间同步 Echo 数据：
 * <ul>
 *   <li>{@link #receiveSnapshot} — 接收对端发来的 .echo 快照，验签 → 合并到本地图谱</li>
 *   <li>{@link #sendSnapshot} — 将本地记忆打包、签名 → 推送到目标对端</li>
 * </ul>
 *
 * <p>隐私原则：P2P 同步<strong>仅在局域网内</strong>进行，不经过任何互联网节点。
 * 快照签名使用 SHA-256 HMAC 防止伪造，内容不做额外加密（依赖局域网物理隔离）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class P2PSyncService {

    private final PersonRepository personRepository;
    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;

    @Value("${echo.p2p.enabled:false}")
    private boolean p2pEnabled;

    @Value("${echo.p2p.port:9090}")
    private int p2pPort;

    /** 设备身份标识（用于快照签名验证，生产环境从密钥库读取） */
    @Value("${echo.p2p.device-secret:echo-default-secret}")
    private String deviceSecret;

    /** 本地设备名称 */
    @Value("${echo.p2p.device-name:echo-device}")
    private String deviceName;

    // ── 状态查询 ──────────────────────────────────────────────

    /**
     * 获取 P2P 同步状态
     *
     * @return 包含启用状态、已连接对端数等信息的 Map
     */
    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", p2pEnabled);
        status.put("port", p2pPort);
        status.put("deviceName", deviceName);
        status.put("connectedPeers", 0);
        status.put("lastSyncTime", lastSyncTime);
        return status;
    }

    /** 最后一次同步时间 */
    private String lastSyncTime;

    // ── 快照接收 ──────────────────────────────────────────────

    /**
     * 接收对端发来的 .echo 快照并合并到本地图谱
     *
     * <p>处理流程：
     * <ol>
     *   <li>验证快照签名（SHA-256 HMAC）</li>
     *   <li>反序列化快照 JSON</li>
     *   <li>合并人物节点（MERGE ON name，更新 mentionCount/lastSeen）</li>
     *   <li>合并对话节点（MERGE ON conversationId，避免重复）</li>
     * </ol>
     *
     * @param snapshotJson  快照 JSON 字符串
     * @param signature     对端提供的签名（Base64 编码的 SHA-256 摘要）
     * @param sourcePeer    来源设备地址
     * @return 合并结果描述
     */
    public Map<String, Object> receiveSnapshot(String snapshotJson, String signature,
                                                String sourcePeer) {
        if (!p2pEnabled) {
            throw new EchoException(EchoException.ErrorCode.P2P_DISABLED);
        }

        log.info("收到 P2P 快照: sourcePeer={}, payloadSize={}", sourcePeer,
                snapshotJson != null ? snapshotJson.length() : 0);

        // 1. 验证签名
        verifySignature(snapshotJson, signature);

        // 2. 解析快照 JSON
        JsonNode snapshot;
        try {
            snapshot = objectMapper.readTree(snapshotJson);
        } catch (Exception e) {
            log.error("P2P 快照 JSON 解析失败: sourcePeer={}", sourcePeer, e);
            throw new EchoException(EchoException.ErrorCode.CALLBACK_INVALID_PAYLOAD,
                    "快照 JSON 格式错误: " + e.getMessage());
        }

        // 3. 合并人物节点
        int mergedPersons = mergePersonNodes(snapshot.path("persons"));

        // 4. 合并对话节点
        int[] convResult = mergeConversationNodes(snapshot.path("conversations"));
        int mergedConversations = convResult[0];
        int skippedDuplicates = convResult[1];

        lastSyncTime = LocalDateTime.now().toString();

        Map<String, Object> result = new HashMap<>();
        result.put("status", "merged");
        result.put("sourcePeer", sourcePeer);
        result.put("mergedPersons", mergedPersons);
        result.put("mergedConversations", mergedConversations);
        result.put("skippedDuplicates", skippedDuplicates);
        result.put("syncedAt", lastSyncTime);

        log.info("P2P 快照合并完成: sourcePeer={}, persons={}, conversations={}",
                sourcePeer, mergedPersons, mergedConversations);
        return result;
    }

    // ── 快照发送 ──────────────────────────────────────────────

    /**
     * 将本地记忆打包为快照并签名发送到对端
     *
     * <p>处理流程：
     * <ol>
     *   <li>查询本地人物节点和近期对话节点</li>
     *   <li>序列化为快照 JSON</li>
     *   <li>计算 SHA-256 签名</li>
     *   <li>通过 WebClient 向对端 POST /api/v1/sync/receive</li>
     * </ol>
     *
     * @param peerAddress 目标设备地址（http://IP:PORT）
     * @return 发送结果描述
     */
    public String sendSnapshot(String peerAddress) {
        if (!p2pEnabled) {
            throw new EchoException(EchoException.ErrorCode.P2P_DISABLED);
        }

        log.info("开始发送 P2P 快照: peerAddress={}", peerAddress);

        // 1. 构建快照数据
        String snapshotJson = buildSnapshotJson();

        // 2. 计算签名
        String signature = computeSignature(snapshotJson);

        // 3. 发送到对端
        try {
            // 构造请求地址（确保以 http:// 开头）
            String baseUrl = peerAddress.startsWith("http") ? peerAddress : "http://" + peerAddress;

            RestTemplate restTemplate = new RestTemplate();
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("snapshot", snapshotJson);
            requestBody.put("signature", signature);
            requestBody.put("sourcePeer", deviceName);

            String response = restTemplate.postForObject(
                    baseUrl + "/api/v1/sync/receive",
                    requestBody,
                    String.class);

            log.info("P2P 快照发送成功: peerAddress={}", peerAddress);
            return "快照发送成功: " + peerAddress + ", 响应: " + response;

        } catch (Exception e) {
            log.error("P2P 快照发送失败: peerAddress={}", peerAddress, e);
            throw new EchoException(EchoException.ErrorCode.P2P_SYNC_FAILED,
                    peerAddress + " - " + e.getMessage());
        }
    }

    /**
     * 触发与指定设备的手动同步（发送快照）
     *
     * @param peerAddress 目标设备地址（IP:PORT）
     * @return 同步结果描述
     */
    public String triggerSync(String peerAddress) {
        if (!p2pEnabled) {
            return "P2P 同步功能未启用，请在配置中设置 echo.p2p.enabled=true";
        }
        try {
            return sendSnapshot(peerAddress);
        } catch (EchoException e) {
            return "同步失败: " + e.getMessage();
        }
    }

    /**
     * 发现局域网内的 Echo 设备
     *
     * @return 已发现设备列表
     */
    public Map<String, Object> discoverPeers() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "not_implemented");
        result.put("message", "设备发现功能开发中，当前请手动配置对端地址");
        // TODO: 使用 mDNS 或广播发现局域网内的 Echo 设备
        return result;
    }

    // ── 私有辅助方法 ──────────────────────────────────────────

    /**
     * 验证快照签名
     *
     * @param payload   原始载荷
     * @param signature 对端提供的签名（Base64）
     */
    private void verifySignature(String payload, String signature) {
        if (signature == null || signature.isBlank()) {
            log.warn("收到无签名的 P2P 快照，拒绝合并");
            throw new EchoException(EchoException.ErrorCode.P2P_SIGNATURE_INVALID);
        }
        try {
            String expectedSignature = computeSignature(payload);
            if (!expectedSignature.equals(signature)) {
                log.warn("P2P 快照签名不匹配");
                throw new EchoException(EchoException.ErrorCode.P2P_SIGNATURE_INVALID);
            }
        } catch (EchoException e) {
            throw e;
        } catch (Exception e) {
            throw new EchoException(EchoException.ErrorCode.P2P_SIGNATURE_INVALID);
        }
    }

    /**
     * 计算 SHA-256 签名
     *
     * @param payload 原始载荷
     * @return Base64 编码的签名
     */
    private String computeSignature(String payload) {
        try {
            String input = payload + ":" + deviceSecret;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (Exception e) {
            throw new EchoException(EchoException.ErrorCode.P2P_SYNC_FAILED, "签名计算失败");
        }
    }

    /**
     * 构建本地数据快照 JSON
     *
     * @return 快照 JSON 字符串
     */
    private String buildSnapshotJson() {
        try {
            List<PersonNode> persons = personRepository.findAll();
            List<ConversationNode> conversations = conversationRepository
                    .findByTimeRange(LocalDateTime.now().minusDays(30), LocalDateTime.now());

            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("deviceName", deviceName);
            snapshot.put("snapshotAt", LocalDateTime.now().toString());
            snapshot.put("persons", persons);
            snapshot.put("conversations", conversations);

            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.error("构建快照 JSON 失败", e);
            throw new EchoException(EchoException.ErrorCode.P2P_SYNC_FAILED, "快照构建失败: " + e.getMessage());
        }
    }

    /**
     * 合并人物节点（以 name 为主键 MERGE）
     *
     * @param personsNode 快照中的 persons JSON 数组
     * @return 合并的节点数量
     */
    private int mergePersonNodes(JsonNode personsNode) {
        if (personsNode == null || personsNode.isMissingNode() || !personsNode.isArray()) {
            return 0;
        }

        int count = 0;
        for (JsonNode personJson : personsNode) {
            try {
                String name = personJson.path("name").asText();
                if (name.isBlank()) continue;

                personRepository.findByName(name).ifPresentOrElse(
                        existing -> {
                            // 保留更大的 mentionCount
                            int remoteMentionCount = personJson.path("mentionCount").asInt(0);
                            if (remoteMentionCount > existing.getMentionCount()) {
                                existing.setMentionCount(remoteMentionCount);
                                personRepository.save(existing);
                            }
                        },
                        () -> {
                            // 创建新节点
                            PersonNode newPerson = PersonNode.builder()
                                    .name(name)
                                    .relationship(personJson.path("relationship").asText(null))
                                    .mentionCount(personJson.path("mentionCount").asInt(1))
                                    .interactionCount(personJson.path("interactionCount").asInt(0))
                                    .firstSeen(LocalDateTime.now())
                                    .lastSeen(LocalDateTime.now())
                                    .build();
                            personRepository.save(newPerson);
                        }
                );
                count++;
            } catch (Exception e) {
                log.warn("合并人物节点失败（跳过）: {}", e.getMessage());
            }
        }
        return count;
    }

    /**
     * 合并对话节点（以 conversationId 为主键 MERGE）
     *
     * @return int[]{mergedCount, skippedDuplicates}
     */
    private int[] mergeConversationNodes(JsonNode conversationsNode) {
        if (conversationsNode == null || conversationsNode.isMissingNode()
                || !conversationsNode.isArray()) {
            return new int[]{0, 0};
        }

        int merged = 0;
        int skipped = 0;
        for (JsonNode convJson : conversationsNode) {
            try {
                String conversationId = convJson.path("conversationId").asText();
                if (conversationId.isBlank()) continue;

                if (conversationRepository.findByConversationId(conversationId).isEmpty()) {
                    ConversationNode newConv = ConversationNode.builder()
                            .conversationId(conversationId)
                            .audioObjectKey(convJson.path("audioObjectKey").asText(null))
                            .transcript(convJson.path("transcript").asText(null))
                            .summary(convJson.path("summary").asText(null))
                            .sentiment(convJson.path("sentiment").asText(null))
                            .status("synced")
                            .recordedAt(LocalDateTime.now())
                            .build();
                    conversationRepository.save(newConv);
                    merged++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("合并对话节点失败（跳过）: {}", e.getMessage());
            }
        }
        return new int[]{merged, skipped};
    }
}
