package com.hify.mcp.service.impl;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.exception.BizException;
import com.hify.mcp.constant.McpErrorCode;
import com.hify.mcp.entity.McpServer;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * MCP Streamable HTTP 传输客户端：JSON-RPC 2.0 over HTTP POST。
 *
 * <p>协议子集（够用即可，不引 MCP SDK）：
 * <ul>
 *   <li>握手：initialize → 响应头 Mcp-Session-Id → notifications/initialized 通知</li>
 *   <li>会话 id 按服务缓存在内存，HTTP 404（会话失效）时重握手一次</li>
 *   <li>响应体两种形态都处理：application/json（单条 JSON-RPC）与 text/event-stream（data: 帧携带响应）</li>
 * </ul>
 *
 * <p>传输层用 OkHttp：请求体按字节数组发出（固定 Content-Length），
 * 避免 HttpURLConnection 对未知长度请求体自动改用 chunked——不少 MCP 服务端
 * （尤其轻量实现）不接受 chunked 请求体。OkHttp 读响应默认按 UTF-8 解码，
 * SSE 响应不带 charset 时中文不会被 ISO-8859-1 破坏。
 *
 * <p>超时外化到 hify.http.mcp.*（建连 + 整响应读超时）。异常统一转
 * {@link BizException}(MCP 段错误码)；JSON-RPC error 统一按协议错误抛，
 * 由上层（对话层报错前缀）给出面向用户的语境。
 */
@Slf4j
@Component
public class McpHttpClient {

    private static final String PROTOCOL_VERSION = "2025-03-26";

    private static final String CLIENT_NAME = "hify";

    private static final String CLIENT_VERSION = "0.0.1";

    /** 掩码值：headers 值回显用，与 provider.api_key 同策略 */
    private static final String MASK = "******";

    private static final MediaType JSON_UTF8 = MediaType.parse("application/json; charset=utf-8");

    private final ObjectMapper objectMapper;

    /** serverId → 会话 id（MCP 握手后服务端分配） */
    private final Map<Long, String> sessionIds = new ConcurrentHashMap<>();

    private final AtomicLong rpcId = new AtomicLong(0);

    private final OkHttpClient okHttpClient;

    public McpHttpClient(
            ObjectMapper objectMapper,
            @Value("${hify.http.mcp.connect-timeout:5s}") Duration connectTimeout,
            @Value("${hify.http.mcp.read-timeout:30s}") Duration readTimeout) {
        this.objectMapper = objectMapper;
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                // 与 LlmHttpClient 相反这里允许重试：很多 MCP 服务端（HTTP/1.0 / 每请求断开）
                // 会让连接池里的旧连接失效，OkHttp 的重试只发生在请求未送达的场景，安全
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 发一次 JSON-RPC 请求并返回 result。自动确保会话（无缓存则握手），
     * 会话失效（HTTP 404）重握手一次后重试。
     *
     * @param params 请求参数；null 时省略
     */
    public JsonNode request(McpServer server, String method, JsonNode params) {
        String session = ensureSession(server);
        try {
            return post(server, session, method, params);
        } catch (SessionExpiredException e) {
            sessionIds.remove(server.getId());
            log.info("MCP 会话失效，重握手 serverId={} url={}", server.getId(), server.getUrl());
            return post(server, ensureSession(server), method, params);
        }
    }

    /** 丢弃会话缓存（服务更新后由 McpServerServiceImpl 调用，避免旧会话/旧 headers 继续用） */
    public void invalidateSession(Long serverId) {
        sessionIds.remove(serverId);
    }

    // ---------- 握手 ----------

    /** 无缓存会话则握手：initialize → 记录 Mcp-Session-Id → 发 initialized 通知 */
    private String ensureSession(McpServer server) {
        String cached = sessionIds.get(server.getId());
        if (cached != null) {
            return cached;
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);

        long id = nextId();
        ObjectNode body = rpcBody(id, "initialize", params);
        ResponsePayload payload = exchange(server, null, body, id);
        JsonNode result = requireResult(payload.node(), server.getUrl());
        if (!result.path("protocolVersion").isTextual()) {
            throw new BizException(McpErrorCode.MCP_PROTOCOL_ERROR,
                    "initialize 响应缺少 protocolVersion，url=" + server.getUrl());
        }
        if (payload.sessionId() != null && !payload.sessionId().isBlank()) {
            sessionIds.put(server.getId(), payload.sessionId());
        }
        // initialized 通知：无 id、无响应体（202），失败不阻断（部分无状态服务端可忽略）
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        try {
            exchange(server, sessionIds.get(server.getId()), notification, null);
        } catch (Exception e) {
            log.debug("initialized 通知失败（忽略）serverId={} 原因={}", server.getId(), e.getMessage());
        }
        return sessionIds.get(server.getId());
    }

    // ---------- 请求 ----------

    private JsonNode post(McpServer server, String session, String method, JsonNode params) {
        long id = nextId();
        ObjectNode body = rpcBody(id, method, params);
        ResponsePayload payload = exchange(server, session, body, id);
        JsonNode response = payload.node();
        if (response == null) {
            throw new BizException(McpErrorCode.MCP_PROTOCOL_ERROR,
                    "响应不是 JSON-RPC 消息，url=" + server.getUrl());
        }
        return requireResult(response, server.getUrl());
    }

    /** 组装并发出一次 POST，处理 404（会话失效）/ 网络 / HTTP 错误分类 */
    private ResponsePayload exchange(McpServer server, String session, ObjectNode body, Long id) {
        long start = System.currentTimeMillis();
        String url = server.getUrl();
        okhttp3.Headers.Builder headerBuilder = new okhttp3.Headers.Builder()
                .set("Content-Type", "application/json")
                .set("Accept", "application/json, text/event-stream");
        userHeaders(server).forEach(headerBuilder::set);
        if (session != null && !session.isBlank()) {
            headerBuilder.set("Mcp-Session-Id", session);
        }

        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new BizException(McpErrorCode.MCP_PROTOCOL_ERROR, "JSON-RPC 请求体序列化失败");
        }

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(payload, JSON_UTF8))
                .headers(headerBuilder.build())
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            int status = response.code();
            if (status == 404 && session != null) {
                // 协议规定：会话失效/不存在时服务端回 404
                throw new SessionExpiredException();
            }
            if (status >= 400) {
                String errorBody = response.body() == null ? "" : response.body().string();
                log.warn("MCP 调用 HTTP 失败 url={} status={} body={}", url, status, truncate(errorBody));
                throw new BizException(McpErrorCode.MCP_CONNECT_FAILED,
                        "MCP 服务 HTTP 错误，status=" + status);
            }
            String contentType = response.header("Content-Type", "");
            String responseBody = response.body() == null ? "" : response.body().string();
            log.info("MCP 调用 url={} method={} 耗时={}ms status={}", url,
                    body.path("method").asText(""), System.currentTimeMillis() - start, status);
            return new ResponsePayload(parseResponse(responseBody, contentType, id),
                    response.header("Mcp-Session-Id"));
        } catch (SessionExpiredException e) {
            throw e;
        } catch (IOException e) {
            log.warn("MCP 调用网络异常 url={} 耗时={}ms 原因={}", url, System.currentTimeMillis() - start, e.getMessage());
            throw new BizException(McpErrorCode.MCP_CONNECT_FAILED, "MCP 服务连接失败：" + e.getMessage());
        }
    }

    /** 响应体兼容 JSON 与 SSE 两种形态；带 id 的请求只取对应 id 的那条响应 */
    private JsonNode parseResponse(String body, String contentType, Long id) {
        if (body == null || body.isBlank()) {
            return null;   // 202 通知等无响应体
        }
        boolean sse = contentType.startsWith("text/event-stream")
                || body.startsWith("data:") || body.startsWith("event:");
        if (!sse) {
            return readTree(body);
        }
        // SSE 帧：逐行取 data:，找 id 匹配的响应（通知行忽略）
        for (String line : body.split("\n")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            JsonNode node = readTree(payload);
            if (id == null || node.path("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }

    /** 响应含 error 时按协议错误抛（语义化归因由调用方处理） */
    private JsonNode requireResult(JsonNode response, String url) {
        JsonNode error = response.path("error");
        if (error.isObject()) {
            throw new BizException(McpErrorCode.MCP_PROTOCOL_ERROR,
                    "JSON-RPC 错误 code=" + error.path("code").asLong() + "："
                            + error.path("message").asText("") + "，url=" + url);
        }
        JsonNode result = response.path("result");
        if (!result.isMissingNode() && !result.isNull()) {
            return result;
        }
        throw new BizException(McpErrorCode.MCP_PROTOCOL_ERROR, "JSON-RPC 响应缺少 result，url=" + url);
    }

    private ObjectNode rpcBody(long id, String method, JsonNode params) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        if (params != null && !params.isNull()) {
            body.set("params", params);
        }
        return body;
    }

    /** 服务上配置的自定义请求头（鉴权等） */
    private Map<String, String> userHeaders(McpServer server) {
        Map<String, String> headers = new LinkedHashMap<>();
        String json = server.getHeaders();
        if (json == null || json.isBlank()) {
            return headers;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BizException(McpErrorCode.MCP_PROTOCOL_ERROR,
                    "headers 配置不是合法 JSON，serverId=" + server.getId());
        }
        if (!node.isObject()) {
            throw new BizException(McpErrorCode.MCP_PROTOCOL_ERROR,
                    "headers 配置必须是 JSON 对象，serverId=" + server.getId());
        }
        node.fields().forEachRemaining(entry -> {
            String value = entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue().toString();
            // 掩码值是前端原样回传的，跳过（真实值只在库里）
            if (!MASK.equals(value)) {
                headers.put(entry.getKey(), value);
            }
        });
        return headers;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BizException(McpErrorCode.MCP_PROTOCOL_ERROR, "响应不是合法 JSON：" + truncate(json));
        }
    }

    private long nextId() {
        return rpcId.incrementAndGet();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 500 ? body.substring(0, 500) + "...(截断)" : body;
    }

    /** 会话失效信号（仅内部使用，exchange 抛出、request 捕获后重握手） */
    private static class SessionExpiredException extends RuntimeException {
    }

    /** 一次 POST 的解析结果：JSON-RPC 消息（可为 null）+ 服务端分配的会话 id */
    private record ResponsePayload(JsonNode node, String sessionId) {
    }
}
