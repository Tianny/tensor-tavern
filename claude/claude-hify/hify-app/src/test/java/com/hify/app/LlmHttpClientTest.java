package com.hify.app;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.http.LlmApiException;
import com.hify.common.http.LlmHttpClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * LlmHttpClient 测试：JDK 内置 HttpServer 起本地桩，不依赖外部服务。
 * 用短超时构造被测对象，让 TIMEOUT 分支能在毫秒级触发。
 */
class LlmHttpClientTest {

    private static HttpServer server;
    private static String base;
    private static LlmHttpClient client;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Map<String, String> HEADERS = Map.of("Authorization", "Bearer test-key");

    @BeforeAll
    static void start() throws IOException {
        // 关闭 JDK HttpURLConnection 的全局 keep-alive 缓存：
        // 401 错误体未排空时复用脏连接会挂到读超时，属于 JDK 客户端已知坑，测试不测连接复用
        System.setProperty("http.keepAlive", "false");
        server = HttpServer.create(new InetSocketAddress(0), 0);

        // 原样返回请求体（验证 JSON 序列化 + 透传）
        server.createContext("/echo", ex -> respond(ex, 200, ex.getRequestBody().readAllBytes()));
        server.createContext("/unauthorized", ex -> respond(ex, 401, "bad key".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/ratelimited", ex -> respond(ex, 429, "slow down".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/server-error", ex -> respond(ex, 500, "boom".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/stream-auth", ex -> respond(ex, 401, "bad key".getBytes(StandardCharsets.UTF_8)));
        // 处理耗时超过客户端读超时（300ms）
        server.createContext("/slow", ex -> {
            try {
                TimeUnit.MILLISECONDS.sleep(1200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "{}".getBytes(StandardCharsets.UTF_8));
        });
        // SSE 流：逐块写出并 flush
        server.createContext("/stream", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                for (String line : new String[]{"data: 1", "", "data: 2", "", "data: [DONE]", ""}) {
                    os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
        });

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        // 读超时 300ms（/slow 睡 1200ms）；流式读超时 300ms
        client = new LlmHttpClient(objectMapper,
                Duration.ofMillis(500), Duration.ofMillis(300), Duration.ofMillis(300));
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static LlmApiException expectFailure(String url) {
        return catchThrowableOfType(() ->
                client.post(url, HEADERS, Map.of("model", "test-model")), LlmApiException.class);
    }

    @Test
    void 正常请求返回响应体且请求体被序列化() throws Exception {
        Map<String, String> body = Map.of("model", "test-model", "stream", "false");

        String resp = client.post(base + "/echo", HEADERS, body);

        assertThat(resp).isEqualTo(objectMapper.writeValueAsString(body));
    }

    @Test
    void 响应401归类为认证失败() {
        LlmApiException e = expectFailure(base + "/unauthorized");

        assertThat(e.getKind()).isEqualTo(LlmApiException.Kind.AUTH_FAILED);
        assertThat(e.getStatusCode()).isEqualTo(401);
    }

    @Test
    void 响应429归类为限流() {
        LlmApiException e = expectFailure(base + "/ratelimited");

        assertThat(e.getKind()).isEqualTo(LlmApiException.Kind.RATE_LIMITED);
        assertThat(e.getStatusCode()).isEqualTo(429);
    }

    @Test
    void 响应5xx归类为调用失败() {
        LlmApiException e = expectFailure(base + "/server-error");

        assertThat(e.getKind()).isEqualTo(LlmApiException.Kind.CALL_FAILED);
        assertThat(e.getStatusCode()).isEqualTo(500);
    }

    @Test
    void 读超时归类为TIMEOUT() {
        LlmApiException e = expectFailure(base + "/slow");

        assertThat(e.getKind()).isEqualTo(LlmApiException.Kind.TIMEOUT);
        assertThat(e.getStatusCode()).isNull();
    }

    @Test
    void 连接超时归类为TIMEOUT() {
        // TEST-NET-1（RFC 5737 保留地址，保证不可路由），500ms 连接超时
        LlmApiException e = expectFailure("http://192.0.2.1:9/llm");

        assertThat(e.getKind()).isEqualTo(LlmApiException.Kind.TIMEOUT);
    }

    @Test
    void 流式请求逐行回调_行按原样透传() {
        List<String> lines = new ArrayList<>();

        client.stream(base + "/stream", HEADERS, Map.of("stream", "true"), lines::add);

        assertThat(lines).containsExactly("data: 1", "", "data: 2", "", "data: [DONE]", "");
    }

    @Test
    void 流式请求鉴权失败() {
        List<String> lines = new ArrayList<>();

        LlmApiException e = catchThrowableOfType(() ->
                client.stream(base + "/stream-auth", HEADERS, Map.of(), lines::add), LlmApiException.class);

        assertThat(e.getKind()).isEqualTo(LlmApiException.Kind.AUTH_FAILED);
        assertThat(lines).isEmpty();
    }
}
