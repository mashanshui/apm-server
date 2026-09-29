package com.shanshui.apmserver.platform.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分析查询独立预算，不改变已有写入及其他业务 HTTP 调用。 */
class ClickHouseHttpClientQueryBudgetTests {

    @Test
    void propagatesLimitsAndRejectsOversizedResponse() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            exchange.getRequestBody().readAllBytes();
            byte[] body = "123456789".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ClickHouseHttpClient client = client(server);
            QueryBudget small = new QueryBudget(1000, 7, 1024, 2048, 8);
            assertEquals("QUERY_RESOURCE_LIMIT", assertThrows(QueryValidationException.class,
                    () -> client.executeQuery("SELECT 1", small)).getCode());
            assertTrue(rawQuery.get().contains("wait_end_of_query=1"));
            assertTrue(rawQuery.get().contains("max_rows_to_read=7"));
            assertTrue(rawQuery.get().contains("max_bytes_to_read=1024"));
            assertTrue(rawQuery.get().contains("max_memory_usage=2048"));
            assertTrue(rawQuery.get().contains("read_overflow_mode=throw"));
            assertEquals("123456789", client.execute("SELECT 1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesDatabaseBudgetErrorsWithoutReturningPartialRows() throws Exception {
        AtomicReference<String> response = new AtomicReference<>("Code: 241. MEMORY_LIMIT_EXCEEDED");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ClickHouseHttpClient client = client(server);
            QueryBudget budget = new QueryBudget(1000, 7, 1024, 2048, 8192);
            assertEquals("QUERY_RESOURCE_LIMIT", assertThrows(QueryValidationException.class,
                    () -> client.executeQuery("SELECT 1", budget)).getCode());
            response.set("Code: 159. TIMEOUT_EXCEEDED");
            assertEquals("QUERY_TIMEOUT", assertThrows(QueryValidationException.class,
                    () -> client.executeQuery("SELECT 1", budget)).getCode());
            response.set("Code: 60. UNKNOWN_TABLE");
            assertThrows(EventStoreUnavailableException.class, () -> client.executeQuery("SELECT 1", budget));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stopsWaitingWhenHttpBudgetExpiresOrCallerCancels() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            received.countDown();
            try {
                Thread.sleep(1500);
                exchange.sendResponseHeaders(200, -1);
            } catch (Exception ignored) {
                // 客户端超时或取消后关闭连接属于此测试的预期路径。
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ClickHouseHttpClient client = client(server);
            QueryBudget shortBudget = new QueryBudget(50, 100, 1024, 2048, 8192);
            assertEquals("QUERY_TIMEOUT", assertThrows(QueryValidationException.class,
                    () -> client.executeQuery("SELECT 1", shortBudget)).getCode());
            AtomicReference<String> cancelled = new AtomicReference<>();
            Thread worker = Thread.startVirtualThread(() -> {
                try {
                    client.executeQuery("SELECT 1", new QueryBudget(5000, 100, 1024, 2048, 8192));
                } catch (QueryValidationException ex) {
                    cancelled.set(ex.getCode());
                }
            });
            assertTrue(received.await(2, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(2000);
            assertTrue(!worker.isAlive());
            assertEquals("QUERY_TIMEOUT", cancelled.get());
        } finally {
            server.stop(0);
        }
    }

    /** 测试服务不接触生产账号和查询数据。 */
    private ClickHouseHttpClient client(HttpServer server) {
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setDatabase("apm");
        properties.setUsername("");
        return new ClickHouseHttpClient(properties);
    }
}
