package com.echo.controlplane.service;

import com.echo.controlplane.exception.EchoException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 数据面 HTTP 客户端
 *
 * <p>控制面通过此客户端调用数据面推理 API（不直接加载 AI 模型）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataPlaneClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${echo.data-plane.base-url:http://localhost:8001}")
    private String dataPlaneBaseUrl;

    /**
     * 调用数据面 /api/v1/embed 对单条文本生成向量
     *
     * @param text 查询文本
     * @return 512 维嵌入向量
     */
    public List<Double> embedText(String text) {
        String url = dataPlaneBaseUrl + "/api/v1/embed";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("texts", List.of(text));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            String responseJson = restTemplate.postForObject(url, entity, String.class);
            if (responseJson == null) {
                throw new EchoException(EchoException.ErrorCode.DATA_PLANE_EMBED_FAILED, "空响应");
            }

            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode embeddings = root.path("embeddings");
            if (!embeddings.isArray() || embeddings.isEmpty()) {
                throw new EchoException(EchoException.ErrorCode.DATA_PLANE_EMBED_FAILED, "无嵌入结果");
            }

            JsonNode vector = embeddings.get(0);
            List<Double> result = objectMapper.convertValue(
                    vector,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class)
            );
            log.debug("文本嵌入完成: dimension={}", result.size());
            return result;

        } catch (EchoException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("调用数据面嵌入 API 失败: {}", e.getMessage());
            throw new EchoException(EchoException.ErrorCode.DATA_PLANE_UNAVAILABLE, e.getMessage());
        } catch (Exception e) {
            log.error("解析嵌入响应失败: {}", e.getMessage());
            throw new EchoException(EchoException.ErrorCode.DATA_PLANE_EMBED_FAILED, e.getMessage());
        }
    }
}
