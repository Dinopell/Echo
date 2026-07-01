package com.echo.controlplane.service;

import com.echo.controlplane.dto.AudioUploadResponse;
import com.echo.controlplane.exception.EchoException;
import com.echo.controlplane.model.ConversationNode;
import com.echo.controlplane.model.TranscribeTask;
import com.echo.controlplane.repository.ConversationRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 音频处理服务
 *
 * <p>职责：
 * <ul>
 *   <li>接收音频文件，AES-256-GCM 加密后上传至 MinIO raw-audio bucket</li>
 *   <li>在 Neo4j 创建对应的 ConversationNode</li>
 *   <li>通过 TaskQueueService 将转写任务推入 Redis 队列</li>
 *   <li>生成音频文件的 MinIO 预签名访问 URL</li>
 * </ul>
 *
 * <p>加密方案：AES-256-GCM（认证加密，确保数据完整性和保密性）。
 * 密钥从环境变量 AUDIO_ENCRYPT_KEY 读取（Base64 编码的 32 字节密钥）。
 *
 * <p>该服务不直接执行 AI 推理，仅负责文件编排和任务分发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioService {

    /** AES-GCM 参数：Tag 长度 128 bit */
    private static final int GCM_TAG_LENGTH = 128;

    /** AES-GCM 参数：IV 长度 12 字节（推荐值） */
    private static final int GCM_IV_LENGTH = 12;

    /** 支持的音频格式 */
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "audio/wav", "audio/x-wav",
            "audio/mpeg", "audio/mp3",
            "audio/mp4", "audio/m4a", "audio/x-m4a"
    );

    private final MinioClient minioClient;
    private final ConversationRepository conversationRepository;
    private final TaskQueueService taskQueueService;

    @Value("${minio.buckets.raw-audio:raw-audio}")
    private String rawAudioBucket;

    /**
     * AES-256-GCM 加密密钥（Base64 编码，32 字节原始密钥）。
     * 若未配置则运行时自动生成临时密钥（仅用于开发环境）。
     */
    @Value("${echo.audio.encrypt-key:}")
    private String encryptKeyBase64;

    /**
     * 上传音频文件并触发转写任务
     *
     * <p>处理流程：
     * <ol>
     *   <li>格式校验（MIME type）</li>
     *   <li>AES-256-GCM 加密音频字节</li>
     *   <li>将加密数据上传至 MinIO raw-audio bucket</li>
     *   <li>在 Neo4j 创建 ConversationNode</li>
     *   <li>向 Redis 队列推送转写任务</li>
     * </ol>
     *
     * @param file     上传的音频文件
     * @param language 语音语言（auto / zh / en）
     * @return 音频上传响应 DTO
     */
    public AudioUploadResponse uploadAudio(MultipartFile file, String language) {
        // 1. 文件非空校验
        if (file == null || file.isEmpty()) {
            throw new EchoException(EchoException.ErrorCode.AUDIO_FILE_EMPTY);
        }

        // 2. 格式校验
        validateAudioFormat(file);

        String conversationId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        // 路径格式: audio/{yyyy}/{MM}/{dd}/{uuid}.enc.{ext}
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String ext = extractAudioExtension(file.getOriginalFilename());
        String objectKey = String.format("audio/%s/%s.enc.%s", datePath, conversationId, ext);

        log.info("开始处理音频上传，conversationId={}, objectKey={}", conversationId, objectKey);

        // 3. AES-256-GCM 加密后上传至 MinIO
        uploadEncryptedToMinio(file, objectKey);

        // 4. 在 Neo4j 创建对话记录节点
        ConversationNode conversation = ConversationNode.builder()
                .conversationId(conversationId)
                .audioObjectKey(objectKey)
                .recordedAt(LocalDateTime.now())
                .status("pending")
                .taskId(taskId)
                .build();
        conversationRepository.save(conversation);

        // 5. 向 Redis 队列推送转写任务（数据面消费）
        TranscribeTask task = TranscribeTask.builder()
                .taskId(taskId)
                .conversationId(conversationId)
                .audioObjectKey(objectKey)
                .bucket(rawAudioBucket)
                .language(language != null ? language : "auto")
                .createdAt(LocalDateTime.now())
                .status("pending")
                .build();
        taskQueueService.pushTranscribeTask(task);

        log.info("音频上传完成，转写任务已入队，taskId={}", taskId);

        return AudioUploadResponse.builder()
                .conversationId(conversationId)
                .taskId(taskId)
                .objectKey(objectKey)
                .status("pending")
                .message("音频已加密上传，转写任务已入队")
                .build();
    }

    /**
     * 兼容旧调用方式（返回 ConversationNode）
     *
     * @param file     上传的音频文件
     * @param language 语音语言
     * @return 创建的对话节点
     */
    public ConversationNode uploadAndTriggerTranscription(MultipartFile file, String language) {
        AudioUploadResponse response = uploadAudio(file, language);
        return conversationRepository.findByConversationId(response.getConversationId())
                .orElseThrow(() -> new EchoException(EchoException.ErrorCode.CONVERSATION_NOT_FOUND,
                        response.getConversationId()));
    }

    /**
     * 生成 MinIO 预签名访问 URL
     *
     * <p>预签名 URL 有效期默认 1 小时，客户端可通过此 URL 直接下载音频文件。
     *
     * @param objectKey  MinIO 对象键
     * @param expireHours 有效期（小时，默认 1）
     * @return 预签名 URL
     */
    public String getAudioUrl(String objectKey, int expireHours) {
        try {
            String url = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(rawAudioBucket)
                            .object(objectKey)
                            .expiry(expireHours, TimeUnit.HOURS)
                            .build()
            );
            log.debug("生成预签名 URL: objectKey={}, expireHours={}", objectKey, expireHours);
            return url;
        } catch (Exception e) {
            log.error("生成预签名 URL 失败: objectKey={}", objectKey, e);
            throw new EchoException(EchoException.ErrorCode.AUDIO_URL_GENERATE_FAILED,
                    e.getMessage());
        }
    }

    /**
     * 生成预签名 URL（默认 1 小时有效期）
     *
     * @param objectKey MinIO 对象键
     * @return 预签名 URL
     */
    public String getAudioUrl(String objectKey) {
        return getAudioUrl(objectKey, 1);
    }

    // ── 私有辅助方法 ──────────────────────────────────────────

    /**
     * 从文件名提取音频扩展名
     */
    private String extractAudioExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "wav";
        }
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "mp3", "mpeg" -> "mp3";
            case "m4a", "mp4" -> "m4a";
            default -> "wav";
        };
    }

    /**
     * 校验音频文件格式
     *
     * @param file 待校验的文件
     */
    private void validateAudioFormat(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !SUPPORTED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            log.warn("不支持的音频格式: contentType={}, filename={}", contentType, file.getOriginalFilename());
            throw new EchoException(EchoException.ErrorCode.AUDIO_FORMAT_NOT_SUPPORTED);
        }
    }

    /**
     * 将音频文件加密后上传到 MinIO
     *
     * <p>加密流程：
     * <ol>
     *   <li>读取文件字节</li>
     *   <li>生成随机 12 字节 IV</li>
     *   <li>AES-256-GCM 加密</li>
     *   <li>将 IV（12B）+ 密文 拼接后上传</li>
     * </ol>
     *
     * @param file      待上传文件
     * @param objectKey 对象存储路径
     */
    private void uploadEncryptedToMinio(MultipartFile file, String objectKey) {
        try {
            // 读取原始音频字节
            byte[] plainBytes = file.getBytes();

            // AES-256-GCM 加密
            byte[] encryptedBytes = encryptAesGcm(plainBytes);

            // 上传加密数据
            try (InputStream inputStream = new ByteArrayInputStream(encryptedBytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(rawAudioBucket)
                                .object(objectKey)
                                .stream(inputStream, encryptedBytes.length, -1)
                                .contentType("application/octet-stream")
                                // 记录原始 MIME 类型供解密后使用
                                .userMetadata(java.util.Map.of(
                                        "x-echo-original-type", file.getContentType() != null
                                                ? file.getContentType() : "audio/wav",
                                        "x-echo-encrypted", "AES-256-GCM"
                                ))
                                .build()
                );
            }
            log.debug("加密文件已上传至 MinIO: bucket={}, key={}, originalSize={}, encryptedSize={}",
                    rawAudioBucket, objectKey, plainBytes.length, encryptedBytes.length);

        } catch (EchoException e) {
            throw e;
        } catch (IOException e) {
            log.error("读取音频文件字节失败: {}", e.getMessage(), e);
            throw new EchoException(EchoException.ErrorCode.AUDIO_UPLOAD_FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("MinIO 上传失败: objectKey={}", objectKey, e);
            throw new EchoException(EchoException.ErrorCode.AUDIO_UPLOAD_FAILED, e.getMessage());
        }
    }

    /**
     * AES-256-GCM 加密
     *
     * <p>输出格式：[12字节 IV] + [GCM 密文（含 16 字节 Tag）]
     *
     * @param plainBytes 原始字节
     * @return 加密后字节（IV 前缀 + 密文）
     */
    private byte[] encryptAesGcm(byte[] plainBytes) {
        try {
            SecretKey secretKey = getOrGenerateEncryptKey();

            // 生成随机 IV（每次加密使用不同 IV，确保语义安全）
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            // 初始化 AES/GCM/NoPadding 密码器
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] cipherBytes = cipher.doFinal(plainBytes);

            // 拼接：IV（12B）+ 密文（原始长度 + 16B Tag）
            byte[] result = new byte[GCM_IV_LENGTH + cipherBytes.length];
            System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
            System.arraycopy(cipherBytes, 0, result, GCM_IV_LENGTH, cipherBytes.length);

            return result;
        } catch (Exception e) {
            log.error("AES-256-GCM 加密失败", e);
            throw new EchoException(EchoException.ErrorCode.AUDIO_ENCRYPT_FAILED, e.getMessage());
        }
    }

    /**
     * 获取或生成加密密钥
     *
     * <p>优先从配置项 echo.audio.encrypt-key 读取 Base64 编码的 32 字节密钥；
     * 若未配置，则运行时自动生成（仅适用于开发环境，重启后密钥变更）。
     *
     * @return AES-256 密钥
     */
    private SecretKey getOrGenerateEncryptKey() {
        try {
            if (encryptKeyBase64 != null && !encryptKeyBase64.isBlank()) {
                // 使用配置的密钥
                byte[] keyBytes = Base64.getDecoder().decode(encryptKeyBase64);
                if (keyBytes.length != 32) {
                    throw new IllegalStateException("echo.audio.encrypt-key 必须是 32 字节（256 bit）Base64 编码密钥");
                }
                return new SecretKeySpec(keyBytes, "AES");
            } else {
                // 开发环境：自动生成临时密钥（生产环境必须配置固定密钥）
                log.warn("未配置 echo.audio.encrypt-key，使用运行时临时密钥（仅开发模式）");
                KeyGenerator keyGen = KeyGenerator.getInstance("AES");
                keyGen.init(256, new SecureRandom());
                return keyGen.generateKey();
            }
        } catch (Exception e) {
            throw new EchoException(EchoException.ErrorCode.AUDIO_ENCRYPT_FAILED, "密钥初始化失败: " + e.getMessage());
        }
    }

    /**
     * HTTP 状态码辅助常量（避免循环引用 EchoException 构造器）
     */
    private static final class HttpStatusHelper {
        static final org.springframework.http.HttpStatus INTERNAL_SERVER_ERROR =
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
