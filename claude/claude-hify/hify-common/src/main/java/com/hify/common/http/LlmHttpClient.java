package com.hify.common.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * LLM HTTP 客户端：普通请求走 RestTemplate，流式（SSE）请求走 OkHttp。
 * 阻塞式 API，调用方必须运行在 llmExecutor 线程池上（见 ThreadPoolConfig）。
 *
 * <p>超时语义（外化到 application.yml 的 hify.http.llm.*）：
 * <ul>
 *   <li>connect-timeout：建连超时，两种客户端一致</li>
 *   <li>read-timeout：RestTemplate 读超时，即普通请求（含等待完整响应）的上限</li>
 *   <li>stream-read-timeout：OkHttp readTimeout，作用在相邻两次读之间，
 *       即流式请求 chunk 间隔的上限，不是总时长</li>
 * </ul>
 *
 * <p>所有请求记录 URL、耗时、状态码；异常统一转 {@link LlmApiException}。
 * 请求体用注入的 Spring ObjectMapper 序列化（注意：不是 Redis 那个带 @class 的）。
 */
@Slf4j
@Component
public class LlmHttpClient {

    private static final okhttp3.MediaType JSON_MEDIA_TYPE =
            okhttp3.MediaType.parse("application/json; charset=utf-8");

    private final ObjectMapper objectMapper;

    private final RestTemplate restTemplate;

    private final OkHttpClient okHttpClient;

    public LlmHttpClient(
            ObjectMapper objectMapper,
            @Value("${hify.http.llm.connect-timeout:5s}") Duration connectTimeout,
            @Value("${hify.http.llm.read-timeout:60s}") Duration readTimeout,
            @Value("${hify.http.llm.stream-read-timeout:120s}") Duration streamReadTimeout) {
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate(connectTimeout, readTimeout);
        this.okHttpClient = buildOkHttpClient(connectTimeout, streamReadTimeout);
    }

    /**
     * 普通同步 POST：等待完整响应后返回 body 字符串。
     *
     * @param url     完整地址
     * @param headers 请求头（鉴权等；Content-Type 自动置为 JSON）
     * @param body    请求体对象，按 JSON 序列化
     */
    public String post(String url, Map<String, String> headers, Object body) {
        long start = System.currentTimeMillis();
        int status = -1;
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            headers.forEach(httpHeaders::set);
            // 显式带 UTF-8：不设 Content-Type 时 StringHttpMessageConverter 默认
            // ISO-8859-1 写字符串，请求体里的中文会全部变成 '?'（embeddings 同样中招）
            httpHeaders.setContentType(
                    new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
            HttpEntity<String> entity = new HttpEntity<>(serialize(body, url), httpHeaders);

            ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
            status = resp.getStatusCode().value();
            log.info("LLM调用 url={} 耗时={}ms status={}", url, System.currentTimeMillis() - start, status);
            return resp.getBody();
        } catch (HttpStatusCodeException e) {
            status = e.getStatusCode().value();
            log.warn("LLM调用失败 url={} 耗时={}ms status={} body={}",
                    url, System.currentTimeMillis() - start, status, truncate(e.getResponseBodyAsString()));
            throw classifyHttpError(url, status, e);
        } catch (ResourceAccessException e) {
            log.warn("LLM调用网络异常 url={} 耗时={}ms 原因={}",
                    url, System.currentTimeMillis() - start, e.getMessage());
            throw classifyNetworkError(url, e);
        }
    }

    /**
     * 普通同步 GET：等待完整响应后返回 body 字符串。
     * 用于供应商连通性探测（GET /models 等）与模型列表拉取。
     *
     * @param url     完整地址
     * @param headers 请求头（鉴权等）
     */
    public String get(String url, Map<String, String> headers) {
        long start = System.currentTimeMillis();
        int status = -1;
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            headers.forEach(httpHeaders::set);
            HttpEntity<Void> entity = new HttpEntity<>(httpHeaders);

            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            status = resp.getStatusCode().value();
            log.info("LLM调用 url={} 耗时={}ms status={}", url, System.currentTimeMillis() - start, status);
            return resp.getBody();
        } catch (HttpStatusCodeException e) {
            status = e.getStatusCode().value();
            log.warn("LLM调用失败 url={} 耗时={}ms status={} body={}",
                    url, System.currentTimeMillis() - start, status, truncate(e.getResponseBodyAsString()));
            throw classifyHttpError(url, status, e);
        } catch (ResourceAccessException e) {
            log.warn("LLM调用网络异常 url={} 耗时={}ms 原因={}",
                    url, System.currentTimeMillis() - start, e.getMessage());
            throw classifyNetworkError(url, e);
        }
    }

    /**
     * 流式 POST（SSE）：逐行回调。行按原样透传（含空行，SSE 的行分隔符），
     * 解析 data: 前缀是调用方的职责。
     * 回调抛出的异常会终止读取并原样向上抛（不算 LLM 调用失败）。
     *
     * @param callback 每读到一行调用一次
     */
    public void stream(String url, Map<String, String> headers, Object body, LineCallback callback) {
        long start = System.currentTimeMillis();
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(serialize(body, url), JSON_MEDIA_TYPE))
                .headers(okhttp3.Headers.of(headers))
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            int status = response.code();
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful() || responseBody == null) {
                log.warn("LLM流式调用失败 url={} 耗时={}ms status={}",
                        url, System.currentTimeMillis() - start, status);
                throw classifyHttpError(url, status, null);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    callback.onLine(line);
                }
            }
            log.info("LLM流式调用 url={} 耗时={}ms status={}", url, System.currentTimeMillis() - start, status);
        } catch (IOException e) {
            // 覆盖建连、读超时（相邻 chunk 间隔超时）、连接被重置等
            log.warn("LLM流式调用网络异常 url={} 耗时={}ms 原因={}",
                    url, System.currentTimeMillis() - start, e.getMessage());
            throw classifyNetworkError(url, e);
        }
    }

    /** 逐行回调 */
    @FunctionalInterface
    public interface LineCallback {

        void onLine(String line);
    }

    // ---------- 内部 ----------

    private RestTemplate buildRestTemplate(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }

    private OkHttpClient buildOkHttpClient(Duration connectTimeout, Duration streamReadTimeout) {
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .readTimeout(streamReadTimeout)
                .retryOnConnectionFailure(false)   // 失败不自动重发（LLM 调用重试由上层策略决定）
                .build();
    }

    private String serialize(Object body, String url) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new LlmApiException(LlmApiException.Kind.CALL_FAILED, url, null,
                    "请求体序列化失败：" + e.getOriginalMessage(), e);
        }
    }

    private LlmApiException classifyHttpError(String url, int status, Exception cause) {
        LlmApiException.Kind kind;
        if (status == 401 || status == 403) {
            kind = LlmApiException.Kind.AUTH_FAILED;
        } else if (status == 429) {
            kind = LlmApiException.Kind.RATE_LIMITED;
        } else {
            kind = LlmApiException.Kind.CALL_FAILED;
        }
        String message = "LLM API 响应错误，status=" + status;
        return cause == null
                ? new LlmApiException(kind, url, status, message)
                : new LlmApiException(kind, url, status, message, cause);
    }

    private LlmApiException classifyNetworkError(String url, Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        // 连接超时、读超时都是 SocketTimeoutException；连接被拒（服务没起）是 ConnectException
        if (root instanceof SocketTimeoutException || root instanceof ConnectException) {
            return new LlmApiException(LlmApiException.Kind.TIMEOUT, url, null,
                    "LLM API 网络超时：" + root.getMessage(), e);
        }
        return new LlmApiException(LlmApiException.Kind.CALL_FAILED, url, null,
                "LLM API 网络异常：" + root.getMessage(), e);
    }

    /** 异常响应体截断，避免日志爆炸 */
    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 500 ? body.substring(0, 500) + "...(截断)" : body;
    }
}
