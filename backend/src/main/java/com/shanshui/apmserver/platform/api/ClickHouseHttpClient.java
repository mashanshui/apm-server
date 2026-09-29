package com.shanshui.apmserver.platform.api;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.net.http.HttpTimeoutException;
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

    /** 分析查询使用独立预算；等待数据库完成后再读取有界响应，避免部分结果被当成成功。 */
    public String executeQuery(String query, QueryBudget budget) {
        if (budget.timeoutMs() < 1 || budget.maxRowsToRead() < 1 || budget.maxBytesToRead() < 1
                || budget.maxMemoryUsage() < 1 || budget.maxResponseBytes() < 1) {
            throw new IllegalArgumentException("查询预算必须为正数");
        }
        String options = "?database=" + encode(properties.getDatabase())
                + "&wait_end_of_query=1&timeout_before_checking_execution_speed=0"
                + "&max_execution_time=" + String.format(Locale.ROOT, "%.3f", budget.timeoutMs() / 1000.0)
                + "&timeout_overflow_mode=throw&read_overflow_mode=throw"
                + "&max_rows_to_read=" + budget.maxRowsToRead()
                + "&max_bytes_to_read=" + budget.maxBytesToRead()
                + "&max_memory_usage=" + budget.maxMemoryUsage();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getUrl() + options))
                    .timeout(Duration.ofMillis(budget.timeoutMs() + 250))
                    .header("Content-Type", "text/plain; charset=utf-8");
            if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
                String credentials = properties.getUsername() + ":" + properties.getPassword();
                builder.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            }
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(query, StandardCharsets.UTF_8)).build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String error = new String(stream.readNBytes(8192), StandardCharsets.UTF_8);
                    if (error.contains("TIMEOUT_EXCEEDED")) {
                        throw new QueryValidationException("QUERY_TIMEOUT", "查询超过执行时间限制", 408);
                    }
                    if (error.contains("MEMORY_LIMIT_EXCEEDED") || error.contains("TOO_MANY_ROWS_OR_BYTES")
                            || error.contains("TOO_MANY_ROWS") || error.contains("TOO_MANY_BYTES")
                            || error.contains("LIMIT_EXCEEDED")) {
                        throw new QueryValidationException("QUERY_RESOURCE_LIMIT", "查询超过资源限制", 422);
                    }
                    throw new EventStoreUnavailableException();
                }
                byte[] bytes = stream.readNBytes(budget.maxResponseBytes() + 1);
                if (bytes.length > budget.maxResponseBytes()) {
                    throw new QueryValidationException("QUERY_RESOURCE_LIMIT", "查询响应超过大小限制", 422);
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (HttpTimeoutException ex) {
            throw new QueryValidationException("QUERY_TIMEOUT", "查询超过执行时间限制", 408);
        } catch (IOException ex) {
            throw new EventStoreUnavailableException();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new QueryValidationException("QUERY_TIMEOUT", "查询已取消", 408);
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
