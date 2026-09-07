package com.shanshui.apmserver.platform.api;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClickHouseHttpClientTests {

    @Test
    void returnsSuccessfulResponseBody() {
        ClickHouseHttpClient client = client(new StubHttpClient(200, "{\"value\":1}", null));

        assertEquals("{\"value\":1}", client.execute("SELECT 1 FORMAT JSONEachRow"));
    }

    @Test
    void mapsRetryableHttpFailureToStoreUnavailable() {
        ClickHouseHttpClient client = client(new StubHttpClient(503, "unavailable", null));

        assertThrows(EventStoreUnavailableException.class, () -> client.execute("SELECT 1"));
    }

    @Test
    void rejectsOtherNonSuccessfulHttpStatus() {
        ClickHouseHttpClient client = client(new StubHttpClient(302, "redirect", null));

        assertThrows(IllegalStateException.class, () -> client.execute("SELECT 1"));
    }

    @Test
    void mapsTimeoutToStoreUnavailable() {
        ClickHouseHttpClient client = client(new StubHttpClient(0, null, new HttpTimeoutException("timeout")));

        assertThrows(EventStoreUnavailableException.class, () -> client.execute("SELECT 1"));
    }

    @Test
    void rejectsResponseWithoutBody() {
        ClickHouseHttpClient client = client(new StubHttpClient(200, null, null));

        assertThrows(IllegalStateException.class, () -> client.execute("SELECT 1"));
    }

    private ClickHouseHttpClient client(HttpClient httpClient) {
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setUrl("http://localhost:8123");
        properties.setDatabase("apm test");
        properties.setUsername("apm");
        properties.setPassword("secret");
        return new ClickHouseHttpClient(properties, httpClient);
    }

    /** 仅控制 HTTP 边界结果，避免单元测试依赖真实 ClickHouse。 */
    private static final class StubHttpClient extends HttpClient {

        private final int statusCode;
        private final String body;
        private final IOException failure;

        private StubHttpClient(int statusCode, String body, IOException failure) {
            this.statusCode = statusCode;
            this.body = body;
            this.failure = failure;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException {
            if (failure != null) {
                throw failure;
            }
            @SuppressWarnings("unchecked")
            T typedBody = (T) body;
            return new StubHttpResponse<>(request, statusCode, typedBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(3));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    private record StubHttpResponse<T>(HttpRequest request, int statusCode, T body) implements HttpResponse<T> {

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
