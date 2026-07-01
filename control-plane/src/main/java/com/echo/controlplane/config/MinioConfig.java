package com.echo.controlplane.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 本地对象存储配置
 *
 * <p>MinIO 在 Echo 项目中的用途：
 * <ul>
 *   <li>raw-audio：存储用户上传的原始音频文件（WAV/MP3/M4A）</li>
 *   <li>transcriptions：存储转写结果文本（JSON 格式）</li>
 *   <li>memory-cards：存储 AI 生成的记忆卡片图片</li>
 *   <li>snapshots：存储设备状态快照（P2P 同步用）</li>
 * </ul>
 *
 * <p>所有数据存储在本地 MinIO，不上传至任何云服务。
 */
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Value("${minio.secure:false}")
    private boolean secure;

    /**
     * 创建 MinIO 客户端 Bean
     *
     * @return 配置好的 MinioClient 实例
     */
    @Bean
    public MinioClient minioClient() {
        String host = endpoint;
        int port = secure ? 443 : 9000;

        // 支持 MINIO_ENDPOINT=minio:9000 或 http://minio:9000
        if (host.contains("://")) {
            host = host.substring(host.indexOf("://") + 3);
        }
        int colon = host.lastIndexOf(':');
        if (colon > 0 && colon < host.length() - 1) {
            port = Integer.parseInt(host.substring(colon + 1));
            host = host.substring(0, colon);
        }

        return MinioClient.builder()
                .endpoint(host, port, secure)
                .credentials(accessKey, secretKey)
                .build();
    }
}
