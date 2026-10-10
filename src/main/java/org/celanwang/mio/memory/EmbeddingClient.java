package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DashScope 文本向量化客户端（JDK HttpClient 直连，AgentScope 2.0.4 无 embedding 支持）。
 * fail-open：任何异常返回 empty 并 warn，调用方静默降级，绝不阻断对话。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";
    private static final String EMBEDDING_PATH = "/api/v1/services/embeddings/text-embedding/text-embedding";
    /** 单条文本截断上限，防超长 episode 报错。 */
    private static final int MAX_TEXT_CHARS = 2000;

    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EmbeddingClient(@Value("${app.dashscope.api-key:}") String apiKey,
                           @Value("${app.dashscope.base-url:}") String baseUrl,
                           @Value("${app.dashscope.embedding-model:text-embedding-v4}") String model,
                           @Value("${app.dashscope.proxy.host:}") String proxyHost,
                           @Value("${app.dashscope.proxy.port:7890}") int proxyPort,
                           ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        String base = StringUtils.hasText(baseUrl) ? baseUrl.trim() : DEFAULT_BASE_URL;
        // 聊天常用 compatible-mode 地址，embedding 用原生 API，去掉已知路径后缀。
        for (String suffix : new String[]{"/compatible-mode", "/api/v1", "/api/v2"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
            }
        }
        this.endpoint = base.replaceAll("/+$", "") + EMBEDDING_PATH;

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15));
        if (StringUtils.hasText(proxyHost)) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost.trim(), proxyPort)));
        }
        this.httpClient = builder.build();
    }

    /** 批量向量化；失败或 key 缺失时返回 empty。返回顺序与输入一致。 */
    public Optional<List<float[]>> embed(List<String> texts) {
        if (!StringUtils.hasText(apiKey) || texts == null || texts.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<String> truncated = texts.stream()
                    .map(t -> t.length() > MAX_TEXT_CHARS ? t.substring(0, MAX_TEXT_CHARS) : t)
                    .toList();
            String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("model", model)
                    .set("input", objectMapper.createObjectNode()
                            .set("texts", objectMapper.valueToTree(truncated))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("embedding 请求失败（fail-open）：HTTP {} {}", response.statusCode(),
                        response.body().length() > 200 ? response.body().substring(0, 200) : response.body());
                return Optional.empty();
            }
            JsonNode embeddings = objectMapper.readTree(response.body()).path("output").path("embeddings");
            if (!embeddings.isArray() || embeddings.size() != truncated.size()) {
                log.warn("embedding 响应条数与输入不符（fail-open）");
                return Optional.empty();
            }
            float[][] vectors = new float[truncated.size()][];
            for (JsonNode item : embeddings) {
                int index = item.path("text_index").asInt();
                JsonNode vector = item.path("embedding");
                float[] values = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) {
                    values[i] = (float) vector.get(i).asDouble();
                }
                vectors[index] = values;
            }
            List<float[]> result = new ArrayList<>(List.of(vectors));
            return result.contains(null) ? Optional.empty() : Optional.of(result);
        } catch (Exception e) {
            log.warn("embedding 调用异常（fail-open）：{}", e.getMessage());
            return Optional.empty();
        }
    }
}
