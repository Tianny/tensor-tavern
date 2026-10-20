package com.hify.chat.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.chat.constant.ChatErrorCode;
import com.hify.chat.dto.ContextMessage;
import com.hify.common.exception.BizException;
import com.hify.common.http.LlmApiException;
import com.hify.common.http.LlmHttpClient;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 对话流适配器：按提供商类型拼 chat 端点请求体 + 解析各家流式增量。
 * 一期支持 openai / openai_compatible / claude；gemini / ollama 后补。
 *
 * <p>function calling：{@link #streamTurn} 接受工具定义（可为 null=不带工具），
 * 返回 {@link TurnOutcome}——正文经 onToken 照常逐字流出，工具调用（openai 的
 * delta.tool_calls 分片 / claude 的 tool_use + input_json_delta 分片）在本层聚合，
 * 由调用方（ChatServiceImpl 的工具循环）执行后把结果作为 {@link ToolResultTurn}
 * 回传进下一轮。
 *
 * <p>调用方（ChatServiceImpl）保证在 llmExecutor 上运行并套熔断/重试。
 * onToken 抛出的异常（客户端断开）会原样向上传播以中止读取。
 */
@Slf4j
@Service
public class LlmStreamService {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** claude 要求 max_tokens 必填且 >0；Agent 配 0（模型默认）时的兜底值 */
    private static final int CLAUDE_DEFAULT_MAX_TOKENS = 4096;

    private final LlmHttpClient httpClient;

    private final ObjectMapper objectMapper;

    public LlmStreamService(
            @Qualifier("llmHttpClient") LlmHttpClient httpClient,
            ObjectMapper objectMapper) {
        // 显式 @Qualifier：上下文里有两个 LlmHttpClient（默认 + probe），必须点名
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** 一次流式调用的 token 用量（拿不到时按内容估算） */
    public record Usage(int promptTokens, int completionTokens) {
    }

    /** 传给 LLM 的工具定义（name 跨 MCP 服务已加前缀保证唯一） */
    public record ToolSpec(String name, String description, JsonNode inputSchema) {
    }

    /** 模型发起的一次工具调用（arguments 为 LLM 生成的 JSON 字符串） */
    public record ToolInvocation(String id, String name, String arguments) {
    }

    /** 一轮流式调用的产物：正文已经过 onToken 流出，工具调用在这里聚合返回 */
    public record TurnOutcome(Usage usage, List<ToolInvocation> toolCalls) {

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    /** function calling 循环的消息模型（比 ContextMessage 多承载工具调用与结果） */
    public sealed interface LoopMessage permits TextTurn, ToolCallTurn, ToolResultTurn {
    }

    /** 纯文本轮（user / assistant） */
    public record TextTurn(String role, String content) implements LoopMessage {
    }

    /** assistant 发起工具调用的一轮（content 可为空串，仅承载 narration） */
    public record ToolCallTurn(String content, List<ToolInvocation> toolCalls) implements LoopMessage {
    }

    /** 一条工具执行结果（tool_call_id 对应 {@link ToolInvocation#id}） */
    public record ToolResultTurn(String toolCallId, String content) implements LoopMessage {
    }

    /**
     * 无工具的流式对话（历史直答路径）。等价于 tools=null 的 {@link #streamTurn}，
     * 保留原签名兼容既有调用。
     */
    public Usage streamChat(Provider provider, ModelConfig model, String systemPrompt,
            List<ContextMessage> messages, BigDecimal temperature, int maxTokens,
            Consumer<String> onToken, Consumer<String> onReasoning) {
        List<LoopMessage> loop = messages.stream()
                .map(m -> (LoopMessage) new TextTurn(m.getRole(), m.getContent()))
                .toList();
        TurnOutcome outcome = streamTurn(provider, model, systemPrompt, loop, null,
                temperature, maxTokens, onToken, onReasoning);
        return outcome.usage();
    }

    /**
     * 一轮流式调用（可带工具定义）。
     *
     * @param tools 工具定义；null/空 = 不带工具（模型只能文字作答）
     * @param onToken     收到正文增量（含工具轮里模型可能先说的话）；抛异常会中止读取
     * @param onReasoning 思考流增量（qwen reasoning_content / claude thinking），非思考型不触发
     */
    public TurnOutcome streamTurn(Provider provider, ModelConfig model, String systemPrompt,
            List<LoopMessage> messages, List<ToolSpec> tools, BigDecimal temperature, int maxTokens,
            Consumer<String> onToken, Consumer<String> onReasoning) {
        StringBuilder received = new StringBuilder();   // 用于拿不到 usage 时的估算
        Consumer<String> countingSink = token -> {
            received.append(token);
            onToken.accept(token);
        };
        TurnOutcome outcome = switch (provider.getType()) {
            case "openai", "openai_compatible" -> openaiStream(provider, model, systemPrompt,
                    messages, tools, temperature, maxTokens, countingSink, onReasoning);
            case "claude" -> claudeStream(provider, model, systemPrompt,
                    messages, tools, temperature, maxTokens, countingSink, onReasoning);
            default -> throw new BizException(ChatErrorCode.CHAT_PROVIDER_NOT_SUPPORTED,
                    "类型 " + provider.getType() + " 的对话流暂未实现");
        };
        Usage usage = outcome.usage();
        if (usage.promptTokens() == 0 || usage.completionTokens() == 0) {
            int estimatedPrompt = estimateTokens(systemPrompt)
                    + messages.stream().mapToInt(m -> estimateTokens(loopText(m))).sum();
            int estimatedCompletion = estimateTokens(received.toString());
            usage = new Usage(
                    usage.promptTokens() > 0 ? usage.promptTokens() : estimatedPrompt,
                    usage.completionTokens() > 0 ? usage.completionTokens() : estimatedCompletion);
        }
        return new TurnOutcome(usage, outcome.toolCalls());
    }

    // ---------- openai / openai_compatible ----------

    private TurnOutcome openaiStream(Provider provider, ModelConfig model, String systemPrompt,
            List<LoopMessage> messages, List<ToolSpec> tools, BigDecimal temperature, int maxTokens,
            Consumer<String> onToken, Consumer<String> onReasoning) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.getModelName());
        body.put("messages", buildOpenAiMessages(systemPrompt, messages));
        body.put("stream", true);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", openaiToolDefs(tools));
            body.put("tool_choice", "auto");
        }
        // 让最后一个 chunk 带 usage，否则流式拿不到 token 数
        body.put("stream_options", Map.of("include_usage", true));

        Map<String, String> headers = new LinkedHashMap<>();
        if (provider.getApiKey() != null && !provider.getApiKey().isEmpty()) {
            headers.put("Authorization", "Bearer " + provider.getApiKey());
        }

        int[] tokens = {0, 0};
        // index → 增量聚合中的 tool_call（id/name 首个分片给出，arguments 逐片拼接）
        Map<Integer, OpenAiToolCallAcc> toolCallAcc = new LinkedHashMap<>();
        httpClient.stream(provider.getBaseUrl() + "/chat/completions", headers, body, line -> {
            if (!line.startsWith("data:")) {
                return;   // 空行、注释、event 行都跳过
            }
            String payload = line.substring(5).trim();
            if ("[DONE]".equals(payload)) {
                return;
            }
            JsonNode root = readTree(payload);
            JsonNode deltaNode = root.path("choices").path(0).path("delta");
            JsonNode delta = deltaNode.path("content");
            if (delta.isTextual()) {
                onToken.accept(delta.asText());
            }
            // 思考型模型（qwen3 / DeepSeek-R1 等）：正文前先流 reasoning_content
            JsonNode reasoning = deltaNode.path("reasoning_content");
            if (reasoning.isTextual()) {
                onReasoning.accept(reasoning.asText());
            }
            JsonNode deltas = deltaNode.path("tool_calls");
            if (deltas.isArray()) {
                for (JsonNode tc : deltas) {
                    OpenAiToolCallAcc acc = toolCallAcc.computeIfAbsent(
                            tc.path("index").asInt(0), k -> new OpenAiToolCallAcc());
                    if (tc.path("id").isTextual()) {
                        acc.id = tc.path("id").asText();
                    }
                    JsonNode fn = tc.path("function");
                    if (fn.path("name").isTextual() && acc.name == null) {
                        acc.name = fn.path("name").asText();
                    }
                    JsonNode args = fn.path("arguments");
                    if (args.isTextual()) {
                        acc.args.append(args.asText());
                    }
                }
            }
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                tokens[0] = usage.path("prompt_tokens").asInt(0);
                tokens[1] = usage.path("completion_tokens").asInt(0);
            }
        });
        return new TurnOutcome(new Usage(tokens[0], tokens[1]), collectToolCalls(toolCallAcc));
    }

    private List<Map<String, Object>> buildOpenAiMessages(String systemPrompt, List<LoopMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            result.add(Map.of("role", "system", "content", systemPrompt));
        }
        for (LoopMessage message : messages) {
            if (message instanceof TextTurn text) {
                result.add(Map.of("role", text.role(), "content", text.content()));
            } else if (message instanceof ToolCallTurn call) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (ToolInvocation invocation : call.toolCalls()) {
                    calls.add(Map.of(
                            "id", invocation.id(),
                            "type", "function",
                            "function", Map.of("name", invocation.name(),
                                    "arguments", invocation.arguments())));
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("content", call.content() == null || call.content().isEmpty() ? null : call.content());
                m.put("tool_calls", calls);
                result.add(m);
            } else if (message instanceof ToolResultTurn tool) {
                result.add(Map.of(
                        "role", "tool",
                        "tool_call_id", tool.toolCallId(),
                        "content", tool.content()));
            }
        }
        return result;
    }

    private List<Map<String, Object>> openaiToolDefs(List<ToolSpec> tools) {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (ToolSpec tool : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.name());
            fn.put("description", tool.description() == null ? "" : tool.description());
            fn.put("parameters", tool.inputSchema() != null && tool.inputSchema().isObject()
                    ? tool.inputSchema() : emptySchema());
            defs.add(Map.of("type", "function", "function", fn));
        }
        return defs;
    }

    /** openai 的 tool_calls 分片聚合状态 */
    private static class OpenAiToolCallAcc {

        String id;

        String name;

        final StringBuilder args = new StringBuilder();
    }

    /** 分片聚合为完整调用；arguments 空时补 "{}"（openai 允许无参工具不传 arguments） */
    private List<ToolInvocation> collectToolCalls(Map<Integer, OpenAiToolCallAcc> acc) {
        return acc.values().stream()
                .filter(a -> a.name != null)
                .map(a -> new ToolInvocation(a.id == null ? "" : a.id, a.name,
                        a.args.isEmpty() ? "{}" : a.args.toString()))
                .toList();
    }

    // ---------- claude ----------

    private TurnOutcome claudeStream(Provider provider, ModelConfig model, String systemPrompt,
            List<LoopMessage> messages, List<ToolSpec> tools, BigDecimal temperature, int maxTokens,
            Consumer<String> onToken, Consumer<String> onReasoning) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.getModelName());
        // claude 的 max_tokens 必填；Agent 配 0 = 模型默认 → 用兜底值
        body.put("max_tokens", maxTokens > 0 ? maxTokens : CLAUDE_DEFAULT_MAX_TOKENS);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", claudeToolDefs(tools));
        }
        body.put("messages", buildClaudeMessages(messages));
        if (temperature != null) {
            // claude 温度范围 0-1，Agent 允许 0-2，超出截断
            body.put("temperature", temperature.min(BigDecimal.ONE).max(BigDecimal.ZERO));
        }
        body.put("stream", true);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", provider.getApiKey());
        headers.put("anthropic-version", ANTHROPIC_VERSION);

        int[] tokens = {0, 0};
        String[] currentEvent = {""};
        // tool_use 块聚合：content_block_start 开块 → input_json_delta 拼参数 → content_block_stop 收口
        ClaudeToolCallAcc[] pending = new ClaudeToolCallAcc[1];
        List<ToolInvocation> completed = new ArrayList<>();
        httpClient.stream(provider.getBaseUrl() + "/v1/messages", headers, body, line -> {
            if (line.startsWith("event:")) {
                currentEvent[0] = line.substring(6).trim();
                return;
            }
            if (!line.startsWith("data:")) {
                return;
            }
            JsonNode root = readTree(line.substring(5).trim());
            switch (currentEvent[0]) {
                case "content_block_start" -> {
                    JsonNode block = root.path("content_block");
                    if ("tool_use".equals(block.path("type").asText())) {
                        pending[0] = new ClaudeToolCallAcc(
                                block.path("id").asText(""), block.path("name").asText(""));
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = root.path("delta");
                    JsonNode text = delta.path("text");
                    if (text.isTextual()) {
                        onToken.accept(text.asText());
                    }
                    // claude 扩展思考：thinking_delta → delta.thinking
                    JsonNode thinking = delta.path("thinking");
                    if (thinking.isTextual()) {
                        onReasoning.accept(thinking.asText());
                    }
                    JsonNode partial = delta.path("partial_json");
                    if (partial.isTextual() && pending[0] != null) {
                        pending[0].args.append(partial.asText());
                    }
                }
                case "content_block_stop" -> {
                    if (pending[0] != null) {
                        ClaudeToolCallAcc acc = pending[0];
                        pending[0] = null;
                        completed.add(new ToolInvocation(acc.id, acc.name,
                                acc.args.isEmpty() ? "{}" : acc.args.toString()));
                    }
                }
                case "message_start" -> tokens[0] = root.path("message").path("usage")
                        .path("input_tokens").asInt(0);
                case "message_delta" -> tokens[1] = root.path("usage").path("output_tokens")
                        .asInt(0);
                case "error" -> throw new LlmApiException(LlmApiException.Kind.CALL_FAILED,
                        provider.getBaseUrl() + "/v1/messages", null,
                        "claude 流返回错误：" + root.path("error").path("message").asText());
                default -> { /* message_stop / ping 等无载荷事件 */ }
            }
        });
        return new TurnOutcome(new Usage(tokens[0], tokens[1]), List.copyOf(completed));
    }

    private List<Map<String, Object>> buildClaudeMessages(List<LoopMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        int lastToolResultIdx = -1;   // 连续 tool_result 合并进同一条 user 消息（claude 要求角色交替）
        for (LoopMessage message : messages) {
            if (message instanceof TextTurn text) {
                result.add(Map.of("role", text.role(), "content", text.content()));
            } else if (message instanceof ToolCallTurn call) {
                List<Object> blocks = new ArrayList<>();
                if (call.content() != null && !call.content().isEmpty()) {
                    blocks.add(Map.of("type", "text", "text", call.content()));
                }
                for (ToolInvocation invocation : call.toolCalls()) {
                    blocks.add(Map.of(
                            "type", "tool_use",
                            "id", invocation.id(),
                            "name", invocation.name(),
                            "input", parseToolArguments(invocation.arguments())));
                }
                result.add(Map.of("role", "assistant", "content", blocks));
            } else if (message instanceof ToolResultTurn tool) {
                if (lastToolResultIdx == result.size() - 1 && result.size() > 0) {
                    // 上一条就是 tool_result 的 user 消息：追加一个块
                    @SuppressWarnings("unchecked")
                    List<Object> blocks = (List<Object>) result.get(lastToolResultIdx).get("content");
                    blocks.add(toolResultBlock(tool));
                } else {
                    List<Object> blocks = new ArrayList<>();
                    blocks.add(toolResultBlock(tool));
                    result.add(Map.of("role", "user", "content", blocks));
                    lastToolResultIdx = result.size() - 1;
                }
            }
        }
        return result;
    }

    private Map<String, Object> toolResultBlock(ToolResultTurn tool) {
        return Map.of(
                "type", "tool_result",
                "tool_use_id", tool.toolCallId(),
                "content", tool.content());
    }

    private List<Map<String, Object>> claudeToolDefs(List<ToolSpec> tools) {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (ToolSpec tool : tools) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", tool.name());
            def.put("description", tool.description() == null ? "" : tool.description());
            def.put("input_schema", tool.inputSchema() != null && tool.inputSchema().isObject()
                    ? tool.inputSchema() : emptySchema());
            defs.add(def);
        }
        return defs;
    }

    /** claude 的 tool_use 块聚合状态 */
    private static class ClaudeToolCallAcc {

        final String id;

        final String name;

        final StringBuilder args = new StringBuilder();

        ClaudeToolCallAcc(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    // ---------- 内部 ----------

    /** LoopMessage 的文本量（token 估算用）：工具结果按 content 算 */
    private static String loopText(LoopMessage message) {
        if (message instanceof TextTurn text) {
            return text.content();
        }
        if (message instanceof ToolCallTurn call) {
            return call.content();
        }
        return ((ToolResultTurn) message).content();
    }

    /** LLM 生成的 arguments 转回 JSON 对象；非法时兜底空对象（claude 的 input 必须是对象） */
    private JsonNode parseToolArguments(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments == null || arguments.isBlank()
                    ? "{}" : arguments);
            return node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode emptySchema() {
        return objectMapper.createObjectNode().put("type", "object");
    }

    private JsonNode readTree(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            // 单行解析失败不终止整条流（截断行 / 非 JSON 行），记 debug 排障
            log.debug("流式响应行解析失败，跳过：{}", payload);
            return objectMapper.createObjectNode();
        }
    }

    /** token 粗估（实现下沉到 hify-common TokenEstimator，knowledge 分块复用同一套启发式） */
    public static int estimateTokens(String text) {
        return com.hify.common.util.TokenEstimator.estimate(text);
    }
}
