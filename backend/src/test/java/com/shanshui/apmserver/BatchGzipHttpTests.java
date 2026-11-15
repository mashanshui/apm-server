package com.shanshui.apmserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离联合验证：APM_GZIP_HTTP_E2E=true 启用，需要 Docker、apm-server-web 镜像和 Android SDK。 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "APM_GZIP_HTTP_E2E", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:gzip-http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "server.address=0.0.0.0"})
class BatchGzipHttpTests extends AppIngestApiTestSupport {
    /** 临时 Servlet 端口。 */
    @LocalServerPort private int port;

    /** 用 SDK 自建会话通过隔离代理上传，同一事件只能接受一次。 */
    @Test void sdkThroughIsolatedNginx() throws Exception {
        resetAppCredential("com.example.app");
        /** 仓库根路径。 */
        Path root = Path.of("..").toAbsolutePath().normalize();
        /** 临时配置，绝不覆盖仓库或现有部署。 */
        Path config = Files.createTempFile("apm-gzip-nginx-", ".conf");
        /** 当前容器标识。 */
        String container = "";
        try {
            Files.writeString(config, Files.readString(root.resolve("docker/nginx.conf"))
                    .replace("backend:8080", "host.docker.internal:" + port)
                    .replace("mcp:3010", "host.docker.internal:" + port));
            container = command("docker", "run", "--rm", "-d", "-p", "127.0.0.1::80", "-v",
                    config + ":/etc/nginx/conf.d/default.conf:ro", "apm-server-web").trim();
            /** Docker 分配的回环地址端口。 */
            String binding = command("docker", "port", container, "80/tcp").trim();
            /** 合成事件时间。 */
            long time = System.currentTimeMillis();
            /** 原始事件信封。 */
            String body = """
                    {"requestId":"gzip-e2e","events":[{"schemaVersion":2,"eventId":"gzip-e2e-start",
                    "eventType":"app_start","occurredAt":%d,"sessionId":"s",
                    "processId":"11111111-1111-4111-8111-111111111111","anonymousDeviceId":"d",
                    "packageName":"com.example.app","appVersion":"1.0","versionCode":1,"buildId":"build",
                    "environment":"test","channel":"test","osVersion":"35","deviceModel":"synthetic"}]}
                    """.formatted(time);
            /** Android 子测试使用现有工程与真正的共享会话。 */
            ProcessBuilder builder = new ProcessBuilder("bash", "android/gradlew", "-p", "android",
                    ":performance-transport:testDebugUnitTest", "--tests", "*BatchGzipE2eTest", "--rerun-tasks", "--console=plain")
                    .directory(root.toFile());
            builder.environment().put("APM_GZIP_E2E_URL", "http://" + binding + "/");
            builder.environment().put("APM_GZIP_E2E_KEY", appKey());
            builder.environment().put("APM_GZIP_E2E_BODY", body);
            /** 保留构建诊断，不输出合成凭据。 */
            Path output = Files.createTempFile("apm-gzip-sdk-", ".log");
            builder.redirectErrorStream(true).redirectOutput(output.toFile());
            /** 受限的联合测试进程。 */
            Process process = builder.start();
            if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("SDK 联合测试超时：" + output); }
            assertEquals(0, process.exitValue(), "SDK 联合测试失败：" + output);
            verifyProxy("http://" + binding + "/", body);

        } finally {
            if (!container.isEmpty()) command("docker", "rm", "-f", container);
            Files.deleteIfExists(config);
        }
    }

    /** 检查代理下未知线上长度、损坏 gzip 和默认请求限制。 */
    private void verifyProxy(String base, String body) throws Exception {
        /** 完整 gzip 的合成字节。 */
        var buffer = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(buffer)) {
            gzip.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        /** HTTP 客户端不自动解码或改写请求。 */
        var client = java.net.http.HttpClient.newHttpClient();
        /** 未声明长度的正文发布器，HTTP/1.1 使用分块传输。 */
        var unknown = java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                () -> new java.io.ByteArrayInputStream(buffer.toByteArray()));
        /** 正确 gzip 分块上传应得到重复确认。 */
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "ingest/v1/batches"))
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .header("X-App-Key", appKey()).header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip").POST(unknown).build();
        assertEquals(200, client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        /** JSON 完整但 CRC 错误，不得接受。 */
        byte[] corrupt = buffer.toByteArray();
        corrupt[corrupt.length - 8] ^= 1;
        assertEquals(400, client.send(java.net.http.HttpRequest.newBuilder(request.uri())
                .header("X-App-Key", appKey()).header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip").POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(corrupt)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        for (boolean chunked : new boolean[] {false, true}) {
            /** 超过后端默认 1 MiB，仍小于代理入口上限。 */
            byte[] oversized = new byte[1024 * 1024 + 1];
            var publisher = chunked ? java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(oversized)) : java.net.http.HttpRequest.BodyPublishers.ofByteArray(oversized);
            assertEquals(413, client.send(java.net.http.HttpRequest.newBuilder(request.uri())
                    .header("X-App-Key", appKey()).header("Content-Type", "application/json").POST(publisher).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    /** 执行隔离容器命令并检查结果，不经过 shell 拼接。 */
    private String command(String... arguments) throws Exception {
        /** Docker 子进程。 */
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        /** 受控命令输出。 */
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
