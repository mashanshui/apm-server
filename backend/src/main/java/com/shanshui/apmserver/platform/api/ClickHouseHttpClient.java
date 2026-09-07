package com.shanshui.apmserver.platform.api;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;

/** 无表名、事件类型和业务判断的 ClickHouse HTTP 基础客户端。 */
@Component
public class ClickHouseHttpClient {

    private final ClickHouseProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public ClickHouseHttpClient(ClickHouseProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    ClickHouseHttpClient(ClickHouseProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    public String execute(String query) {
        return execute(query, "");
    }

    public String execute(String query, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getUrl() + "?database=" + encode(properties.getDatabase())))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "text/plain; charset=utf-8");
            if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
                String credentials = properties.getUsername() + ":" + properties.getPassword();
                builder.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            }
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(
                    body.isBlank() ? query : query + "\n" + body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 500 || response.statusCode() == 429) {
                throw new EventStoreUnavailableException();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("ClickHouse 请求被拒绝，状态码=" + response.statusCode());
            }
            if (response.body() == null) {
                throw new IllegalStateException("ClickHouse 返回了无效响应");
            }
            return response.body();
        } catch (IOException ex) {
            throw new EventStoreUnavailableException();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EventStoreUnavailableException();
        }
    }

    /** 将 ClickHouse JSONEachRow 响应解码为不可变行列表。 */
    public List<JsonNode> decodeJsonEachRow(String response, ObjectMapper objectMapper) {
        List<JsonNode> rows = new ArrayList<>();
        if (response == null || response.isBlank()) {
            return List.of();
        }
        try {
            for (String line : response.split("\\R")) {
                if (!line.isBlank()) {
                    rows.add(objectMapper.readTree(line));
                }
            }
            return List.copyOf(rows);
        } catch (RuntimeException ex) {
            throw new EventStoreUnavailableException("ClickHouse JSONEachRow 响应无效", ex);
        }
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
