package com.echo.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Project Echo 控制面启动类
 *
 * <p>控制面职责：
 * <ul>
 *   <li>接收客户端音频上传请求，将文件存入 MinIO</li>
 *   <li>向 Redis 队列推送 AI 处理任务（转写、摘要、嵌入）</li>
 *   <li>管理 Neo4j 知识图谱（人物节点、对话节点）</li>
 *   <li>提供 P2P 设备间局域网同步能力</li>
 * </ul>
 *
 * <p>隐私优先原则：控制面不直接调用任何 AI 模型，
 * 所有 AI 推理通过 Redis 队列委托给 data-plane 执行。
 */
@SpringBootApplication
public class EchoControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchoControlPlaneApplication.class, args);
    }
}
