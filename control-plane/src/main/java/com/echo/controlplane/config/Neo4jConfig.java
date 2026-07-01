package com.echo.controlplane.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j 知识图谱数据库配置
 *
 * <p>Neo4j 在 Echo 项目中存储的图结构：
 * <pre>
 *   (Person)-[:MENTIONED_IN]->(Conversation)
 *   (Person)-[:KNOWS]->(Person)
 *   (Conversation)-[:NEXT]->(Conversation)
 * </pre>
 *
 * <p>数据库运行在本地 Docker 容器中，不依赖任何云数据库服务。
 */
@Configuration
public class Neo4jConfig {

    @Value("${spring.neo4j.uri}")
    private String uri;

    @Value("${spring.neo4j.authentication.username}")
    private String username;

    @Value("${spring.neo4j.authentication.password}")
    private String password;

    /**
     * 创建 Neo4j Driver Bean（用于底层 Cypher 查询）
     *
     * @return 配置好的 Neo4j Driver 实例
     */
    @Bean
    public Driver neo4jDriver() {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }
}
