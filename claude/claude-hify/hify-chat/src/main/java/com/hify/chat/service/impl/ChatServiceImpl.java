package com.hify.chat.service.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.agent.entity.Agent;
import com.hify.agent.service.AgentService;
import com.hify.chat.constant.ChatErrorCode;
import com.hify.chat.dto.ChatMessageResp;
import com.hify.chat.dto.ChatSessionCreateReq;
import com.hify.chat.dto.ChatSessionResp;
import com.hify.chat.dto.ChatStreamResult;
import com.hify.chat.dto.ContextMessage;
import com.hify.chat.dto.ToolCallEvent;
import com.hify.chat.dto.ToolResultEvent;
import com.hify.chat.entity.ChatMessage;
import com.hify.chat.entity.ChatSession;
import com.hify.chat.exception.StreamAbortedException;
import com.hify.chat.mapper.ChatMessageMapper;
import com.hify.chat.mapper.ChatSessionMapper;
import com.hify.chat.service.ChatService;
import com.hify.chat.service.ChatStreamListener;
import com.hify.chat.service.LlmStreamService;
import com.hify.common.exception.BizException;
import com.hify.common.http.LlmApiException;
import com.hify.common.metrics.HifyMetrics;
import com.hify.common.resilience.CircuitBreakerService;
import com.hify.common.util.RedisUtil;
import com.hify.knowledge.dto.RetrievalContext;
import com.hify.knowledge.service.KnowledgeRetrievalService;
import com.hify.mcp.dto.ToolInfoResp;
import com.hify.mcp.entity.AgentTool;
import com.hify.mcp.service.McpServerService;
import com.hify.mcp.service.McpToolService;
import com.hify.workflow.entity.WorkflowRun;
import com.hify.workflow.service.WorkflowRunService;
import com.hify.workflow.service.WorkflowService;
import com.hify.provider.entity.ModelConfig;
import com.hify.provider.entity.Provider;
import com.hify.provider.service.ModelConfigService;
import com.hify.provider.service.ProviderService;
import com.hify.provider.service.ProviderHttpService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 对话服务实现。流式链路（CLAUDE.md）：请求线程只做校验/落库/装配上下文，
 * LLM 调用提交 llmExecutor，套按 Provider 隔离的熔断器 + 分类重试。
 *
 * <p>上下文：Redis session:{id}（TTL 2h，活跃续期）为热副本，MySQL chat_message
 * 为事实源，Redis 未命中时重建。system prompt 不入上下文，每次从 Agent 配置现取。
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private static final String SESSION_KEY_PREFIX = "session:";

    private static final String ROLE_USER = "user";

    private static final String ROLE_ASSISTANT = "assistant";

    private static final String DEFAULT_TITLE = "新对话";

    private static final int TITLE_MAX = 32;

    private static final Set<String> SUPPORTED_TYPES = Set.of("openai", "openai_compatible", "claude");

    private static final String BREAKER_PREFIX = "provider:";

    private final ChatSessionMapper sessionMapper;

    private final ChatMessageMapper messageMapper;

    private final AgentService agentService;

    private final ModelConfigService modelConfigService;

    private final ProviderService providerService;

    private final LlmStreamService llmStreamService;

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    private final WorkflowService workflowService;

    private final WorkflowRunService workflowRunService;

    private final McpToolService mcpToolService;

    private final McpServerService mcpServerService;

    private final CircuitBreakerService circuitBreakerService;

    private final RedisUtil redisUtil;

    private final ThreadPoolTaskExecutor llmExecutor;

    private final HifyMetrics hifyMetrics;

    @Value("${hify.chat.context-ttl:2h}")
    private Duration contextTtl;

    @Value("${hify.chat.context-max-history:50}")
    private int maxHistory;

    @Value("${hify.chat.context-default-tokens:32768}")
    private int defaultContextTokens;

    /** function calling 的最大 LLM 轮数（最后一轮强制收尾作答） */
    @Value("${hify.chat.tool-max-rounds:5}")
    private int toolMaxRounds;

    public ChatServiceImpl(
            ChatSessionMapper sessionMapper,
            ChatMessageMapper messageMapper,
            AgentService agentService,
            ModelConfigService modelConfigService,
            ProviderService providerService,
            LlmStreamService llmStreamService,
            KnowledgeRetrievalService knowledgeRetrievalService,
            WorkflowService workflowService,
            WorkflowRunService workflowRunService,
            McpToolService mcpToolService,
            McpServerService mcpServerService,
            CircuitBreakerService circuitBreakerService,
            RedisUtil redisUtil,
            HifyMetrics hifyMetrics,
            @Qualifier("llmExecutor") ThreadPoolTaskExecutor llmExecutor) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.agentService = agentService;
        this.modelConfigService = modelConfigService;
        this.providerService = providerService;
        this.llmStreamService = llmStreamService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.workflowService = workflowService;
        this.workflowRunService = workflowRunService;
        this.mcpToolService = mcpToolService;
        this.mcpServerService = mcpServerService;
        this.circuitBreakerService = circuitBreakerService;
        this.redisUtil = redisUtil;
        this.hifyMetrics = hifyMetrics;
        this.llmExecutor = llmExecutor;
    }

    // ---------- 会话 ----------

    @Override
    public ChatSessionResp createSession(ChatSessionCreateReq req) {
        ChatSession session = new ChatSession();
        if (req.getWorkflowId() != null) {
            // 工作流会话：agentId 不允许同时设置；入口契约（start 首变量）此刻校验
            if (req.getAgentId() != null) {
                throw new BizException(ChatErrorCode.CHAT_SESSION_TARGET_INVALID,
                        "agentId 与 workflowId 只能二选一");
            }
            workflowService.getEntity(req.getWorkflowId());   // 不存在抛 6001
            workflowService.chatEntryVariable(req.getWorkflowId());   // 契约不满足抛 6011
            session.setWorkflowId(req.getWorkflowId());
        } else {
            Agent agent = agentService.getEntity(req.getAgentId());   // 不存在抛 3001
            if (agent.getEnabled() != 1) {
                throw new BizException(ChatErrorCode.CHAT_AGENT_DISABLED, "Agent 已停用：" + agent.getName());
            }
            session.setAgentId(agent.getId());
        }
        session.setTitle(StringUtils.hasText(req.getTitle()) ? req.getTitle().trim() : DEFAULT_TITLE);
        sessionMapper.insert(session);
        return ChatSessionResp.of(session);
    }

    @Override
    public List<ChatSessionResp> listSessions(Long agentId, Long workflowId) {
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        if (agentId != null) {
            wrapper.eq(ChatSession::getAgentId, agentId);
        }
        if (workflowId != null) {
            wrapper.eq(ChatSession::getWorkflowId, workflowId);
        }
        wrapper.orderByDesc(ChatSession::getUpdatedAt);
        return sessionMapper.selectList(wrapper).stream()
                .map(ChatSessionResp::of)
                .toList();
    }

    @Override
    public void deleteSession(Long id) {
        if (sessionMapper.selectById(id) == null) {
            throw new BizException(ChatErrorCode.CHAT_SESSION_NOT_FOUND, "会话不存在，id=" + id);
        }
        sessionMapper.deleteById(id);   // 逻辑删除，消息保留（会话没了入口即不可达）
        redisUtil.delete(SESSION_KEY_PREFIX + id);
    }

    // ---------- 消息 ----------

    @Override
    public List<ChatMessageResp> listMessages(Long sessionId, Long before, Integer limit) {
        requireSession(sessionId);
        int size = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByDesc(ChatMessage::getId)   // 游标分页：id 倒序，避开 COUNT(*)
                .last("LIMIT " + size);
        if (before != null) {
            wrapper.lt(ChatMessage::getId, before);
        }
        List<ChatMessage> messages = messageMapper.selectList(wrapper);
        // 倒序取出后反转为时间正序，前端直接渲染
        List<ChatMessageResp> result = new ArrayList<>(messages.size());
        for (int i = messages.size() - 1; i >= 0; i--) {
            result.add(ChatMessageResp.of(messages.get(i)));
        }
        return result;
    }

    // ---------- 流式对话 ----------

    @Override
    public CompletableFuture<ChatStreamResult> streamChat(Long sessionId, String content,
            ChatStreamListener listener) {
        // ---- 同步段：校验 + 落库 + 装配（请求线程，毫秒级）----
        ChatSession session = requireSession(sessionId);
        if (session.getWorkflowId() != null) {
            return streamWorkflowChat(session, content, listener);
        }
        Agent agent = agentService.getEntity(session.getAgentId());
        if (agent.getEnabled() != 1) {
            throw new BizException(ChatErrorCode.CHAT_AGENT_DISABLED, "Agent 已停用：" + agent.getName());
        }
        ModelConfig model = modelConfigService.getById(agent.getModelConfigId());
        if (model == null || model.getEnabled() != 1) {
            throw new BizException(ChatErrorCode.CHAT_MODEL_DISABLED, "Agent 绑定的模型不可用，id=" + agent.getModelConfigId());
        }
        Provider provider = providerService.getEntity(model.getProviderId());
        if (provider.getEnabled() != 1) {
            throw new BizException(ChatErrorCode.CHAT_MODEL_DISABLED, "提供商已停用：" + provider.getName());
        }
        if (!SUPPORTED_TYPES.contains(provider.getType())) {
            throw new BizException(ChatErrorCode.CHAT_PROVIDER_NOT_SUPPORTED,
                    "类型 " + provider.getType() + " 暂不支持对话流");
        }

        // 用户消息先落库（发出即持久，LLM 失败也不丢）
        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(content);
        messageMapper.insert(userMessage);

        // 历史上下文（不含刚插入的本条）+ 首条消息生成会话标题
        List<ContextMessage> history = loadHistory(sessionId, userMessage.getId());
        touchTitleAndSession(session, history, content);

        List<ContextMessage> llmMessages = new ArrayList<>(
                trimByBudget(history, contextBudget(model)));
        llmMessages.add(new ContextMessage(ROLE_USER, content));

        // ---- 异步段：RAG 检索（embedding 是 LLM 调用，同样走 llmExecutor）+ 熔断 + 流式调用 ----
        // 绑定了 MCP 工具的 Agent 走 function calling 循环（每轮照常流式）
        long requestStart = System.currentTimeMillis();
        String agentTag = String.valueOf(agent.getId());
        return CompletableFuture.supplyAsync(() -> {
            List<AgentTool> toolBindings = mcpToolService.listAgentTools(agent.getId());
            if (toolBindings.isEmpty()) {
                return runStream(sessionId, agent, model, provider, llmMessages, listener);
            }
            return runToolStream(sessionId, agent, model, provider, llmMessages, toolBindings, listener);
        }, llmExecutor).whenComplete((result, err) -> hifyMetrics.recordChatRequest(
                agentTag, System.currentTimeMillis() - requestStart,
                err == null && result != null && result.isOk()));
    }

    /** 工作流会话的同步段：校验入口 + 落库用户消息，异步段执行工作流 */
    private CompletableFuture<ChatStreamResult> streamWorkflowChat(ChatSession session, String content,
            ChatStreamListener listener) {
        // 入口契约此刻校验（工作流可能在建会话后被改坏），失败直接走全局异常回 JSON
        String entryVariable = workflowService.chatEntryVariable(session.getWorkflowId());

        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(session.getId());
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(content);
        messageMapper.insert(userMessage);

        List<ContextMessage> history = loadHistory(session.getId(), userMessage.getId());
        touchTitleAndSession(session, history, content);

        Long workflowId = session.getWorkflowId();
        long requestStart = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(
                () -> runWorkflowStream(session.getId(), workflowId, entryVariable,
                        history, content, listener),
                llmExecutor).whenComplete((result, err) -> hifyMetrics.recordChatRequest(
                "workflow", System.currentTimeMillis() - requestStart,
                err == null && result != null && result.isOk()));
    }

    /**
     * 工作流会话的一次完整生成（llmExecutor 线程上）。节点进度走思考流（onReasoning），
     * 最终输出一次性推送（工作流 LLM 节点是非流式的）。执行失败按用户选择不降级：
     * 归一成 ok=false 的结果，前端收到 error 事件。
     */
    private ChatStreamResult runWorkflowStream(Long sessionId, Long workflowId, String entryVariable,
            List<ContextMessage> history, String userContent, ChatStreamListener listener) {
        long start = System.currentTimeMillis();
        StringBuilder content = new StringBuilder();
        try {
            WorkflowRun run = workflowRunService.runInline(workflowId,
                    Map.of(entryVariable, userContent),
                    step -> {
                        // 节点进度借用思考流通道推给前端（灰色小字区）
                        listener.onReasoning(step + "\n");
                    });
            if (WorkflowRun.STATUS_FAILED.equals(run.getStatus())) {
                log.warn("对话触发的工作流失败 sessionId={} workflowId={} 原因={}",
                        sessionId, workflowId, run.getErrorMsg());
                return finish(sessionId, history, userContent, false,
                        "工作流执行失败：" + run.getErrorMsg(), content, start, null);
            }
            content.append(run.getOutput());
            listener.onToken(run.getOutput());
            return finish(sessionId, history, userContent, true, null, content, start, null);
        } catch (StreamAbortedException e) {
            // 客户端主动断开：保留已推送内容
            log.info("工作流对话流被客户端中止 sessionId={} 已生成{}字符", sessionId, content.length());
            return finish(sessionId, history, userContent, false, null, content, start, null);
        } catch (BizException e) {
            // 工作流停用 / 入口契约在会话存续期间被改坏等
            log.warn("对话触发的工作流不可用 sessionId={} workflowId={} 原因={}",
                    sessionId, workflowId, e.getMessage());
            return finish(sessionId, history, userContent, false, e.getMessage(), content, start, null);
        } catch (Exception e) {
            log.error("对话触发的工作流异常 sessionId={} workflowId={}", sessionId, workflowId, e);
            return finish(sessionId, history, userContent, false,
                    "工作流执行异常：" + e.getMessage(), content, start, null);
        }
    }

    /** Agent 的 system prompt + 可选的 RAG 参考资料（检索异常时静默降级为纯 prompt） */
    private String buildSystemPrompt(Agent agent, String query) {
        String prompt = agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt();
        try {
            RetrievalContext retrieval = knowledgeRetrievalService.retrieveForAgent(agent.getId(), query);
            if (retrieval != null && retrieval.hasHits()) {
                log.info("RAG 命中 agentId={} 命中数={}", agent.getId(), retrieval.getHits().size());
                return prompt.isEmpty() ? retrieval.getPrompt() : prompt + "\n\n" + retrieval.getPrompt();
            }
        } catch (Exception e) {
            log.warn("RAG 检索失败，本次对话降级为直答 agentId={} 原因={}", agent.getId(), e.getMessage());
        }
        return prompt;
    }

    /** llmExecutor 线程上执行的一次完整生成；所有失败都归一成 ok=false 的结果 */
    private ChatStreamResult runStream(Long sessionId, Agent agent, ModelConfig model,
            Provider provider, List<ContextMessage> llmMessages, ChatStreamListener listener) {
        long start = System.currentTimeMillis();
        StringBuilder content = new StringBuilder();
        AtomicBoolean tokenSent = new AtomicBoolean(false);
        // 本轮用户消息是 llmMessages 最后一条；历史 = 去掉最后一条（成功后才追加进上下文）
        String userContent = llmMessages.get(llmMessages.size() - 1).getContent();
        List<ContextMessage> history = llmMessages.subList(0, llmMessages.size() - 1);
        // RAG 检索（同在 llmExecutor，检索失败降级为直答）
        String systemPrompt = buildSystemPrompt(agent, userContent);
        LlmStreamService.Usage usage;
        log.info("LLM 调用开始 sessionId={} provider={} model={}",
                sessionId, provider.getName(), model.getModelName());
        long llmStart = System.currentTimeMillis();
        try {
            usage = withLlmMetrics(provider, model, () -> circuitBreakerService.execute(
                    BREAKER_PREFIX + provider.getId(),
                    () -> llmStreamService.streamChat(provider, model, systemPrompt,
                            llmMessages, agent.getTemperature(), agent.getMaxTokens() == null ? 0 : agent.getMaxTokens(),
                            delta -> {
                                tokenSent.set(true);
                                content.append(delta);
                                listener.onToken(delta);   // 客户端断开在这里抛 StreamAbortedException
                            },
                            delta -> {
                                // 思考流已推送同样不允许重试（重发会重复）
                                tokenSent.set(true);
                                listener.onReasoning(delta);
                            }),
                    // 已推送过 token 的流中断不重试（重发内容会重复），见 CircuitBreakerService
                    () -> !tokenSent.get()));
        } catch (StreamAbortedException e) {
            // 客户端主动断开：不算 Provider 失败（熔断器已忽略），保留已生成内容
            log.info("对话流被客户端中止 sessionId={} 已生成{}字符", sessionId, content.length());
            return finish(sessionId, history, userContent, false, null, content, start, null);
        } catch (LlmApiException e) {
            log.warn("对话流失败 sessionId={} provider={} 原因={}", sessionId, provider.getName(), e.getMessage());
            return finish(sessionId, history, userContent, false,
                    ProviderHttpService.describeFailure(e), content, start, null);
        } catch (BizException e) {
            // 熔断开启等
            return finish(sessionId, history, userContent, false, e.getMessage(), content, start, null);
        }
        log.info("LLM 调用结束 sessionId={} provider={} 耗时={}ms tokens={}/{}",
                sessionId, provider.getName(), System.currentTimeMillis() - llmStart,
                usage.promptTokens(), usage.completionTokens());
        return finish(sessionId, history, userContent, true, null, content, start, usage);
    }

    // ---------- function calling 工具循环 ----------

    /**
     * LLM 逻辑调用指标外壳：包在熔断 + 重试外壳外层，重试（含熔断快速失败）不重复计数。
     * 客户端断流（StreamAbortedException）单列 aborted，不污染 Provider 失败率。
     */
    private <T> T withLlmMetrics(Provider provider, ModelConfig model, java.util.function.Supplier<T> call) {
        long start = System.currentTimeMillis();
        try {
            T result = call.get();
            hifyMetrics.recordLlmCall(provider.getName(), model.getModelName(),
                    System.currentTimeMillis() - start, "success");
            return result;
        } catch (StreamAbortedException e) {
            hifyMetrics.recordLlmCall(provider.getName(), model.getModelName(),
                    System.currentTimeMillis() - start, "aborted");
            throw e;
        } catch (Exception e) {
            hifyMetrics.recordLlmCall(provider.getName(), model.getModelName(),
                    System.currentTimeMillis() - start, "failure");
            throw e;
        }
    }

    /** 工具名路由：LLM 侧看到的前缀工具名 → 实际执行的 MCP 服务与工具 */
    private record ToolDispatch(Long serverId, String serverName, String realToolName) {
    }

    /**
     * 绑定了 MCP 工具的 Agent 的一次完整生成（llmExecutor 线程上，全流式）。
     * 每轮流式调用带工具定义；模型发起 tool_calls → 经 MCP 执行 → 结果回传下一轮，
     * 直到模型给出纯文字回答。共 {@code toolMaxRounds} 轮上限：倒数第二轮的工具结果
     * 里附带"直接作答"提示收尾（claude 不允许 tool_result 消息出现在无 tools 参数的
     * 请求里，因此不摘工具定义而是用提示逼出文字）。工具执行失败按用户选择报错不降级。
     */
    private ChatStreamResult runToolStream(Long sessionId, Agent agent, ModelConfig model,
            Provider provider, List<ContextMessage> llmMessages, List<AgentTool> toolBindings,
            ChatStreamListener listener) {
        long start = System.currentTimeMillis();
        StringBuilder content = new StringBuilder();
        AtomicBoolean tokenSent = new AtomicBoolean(false);
        String userContent = llmMessages.get(llmMessages.size() - 1).getContent();
        List<ContextMessage> history = llmMessages.subList(0, llmMessages.size() - 1);

        // 绑定 → 工具定义（前缀 srv{serverId}_ 保证跨服务不重名；服务缺失/停用在此报错）
        List<LlmStreamService.ToolSpec> specs = new ArrayList<>();
        Map<String, ToolDispatch> dispatch = new LinkedHashMap<>();
        for (AgentTool binding : toolBindings) {
            String serverName = mcpServerService.getEntity(binding.getMcpServerId()).getName();
            for (ToolInfoResp tool : mcpToolService.listTools(binding.getMcpServerId())) {
                if (binding.getToolName() != null && !binding.getToolName().equals(tool.getName())) {
                    continue;   // 绑定了具体工具名时只透出该工具
                }
                String llmName = "srv" + binding.getMcpServerId() + "_" + tool.getName();
                specs.add(new LlmStreamService.ToolSpec(llmName, tool.getDescription(), tool.getInputSchema()));
                dispatch.put(llmName, new ToolDispatch(binding.getMcpServerId(), serverName, tool.getName()));
            }
        }

        List<LlmStreamService.LoopMessage> loopMessages = new ArrayList<>();
        for (ContextMessage message : history) {
            loopMessages.add(new LlmStreamService.TextTurn(message.getRole(), message.getContent()));
        }
        loopMessages.add(new LlmStreamService.TextTurn(ROLE_USER, userContent));

        String systemPrompt = buildSystemPrompt(agent, userContent);
        int promptTokens = 0;
        int completionTokens = 0;
        try {
            for (int round = 1; round <= toolMaxRounds; round++) {
                StringBuilder turnText = new StringBuilder();   // 本轮流出的文字（工具轮的 narration 要回传下一轮）
                log.info("LLM 调用开始 sessionId={} provider={} model={} 第{}轮(共{}轮上限)",
                        sessionId, provider.getName(), model.getModelName(), round, toolMaxRounds);
                long llmStart = System.currentTimeMillis();
                LlmStreamService.TurnOutcome outcome = withLlmMetrics(provider, model,
                        () -> circuitBreakerService.execute(
                        BREAKER_PREFIX + provider.getId(),
                        () -> llmStreamService.streamTurn(provider, model, systemPrompt, loopMessages,
                                specs, agent.getTemperature(),
                                agent.getMaxTokens() == null ? 0 : agent.getMaxTokens(),
                                delta -> {
                                    turnText.append(delta);
                                    content.append(delta);
                                    tokenSent.set(true);
                                    listener.onToken(delta);   // 客户端断开在这里抛 StreamAbortedException
                                },
                                delta -> {
                                    tokenSent.set(true);
                                    listener.onReasoning(delta);
                                }),
                        () -> !tokenSent.get()));
                promptTokens += outcome.usage().promptTokens();
                completionTokens += outcome.usage().completionTokens();
                log.info("LLM 调用结束 sessionId={} 第{}轮 耗时={}ms tokens={}/{} toolCalls={}",
                        sessionId, round, System.currentTimeMillis() - llmStart,
                        outcome.usage().promptTokens(), outcome.usage().completionTokens(),
                        outcome.toolCalls() == null ? 0 : outcome.toolCalls().size());
                if (!outcome.hasToolCalls()) {
                    // 纯文字回答：流式已推完，正常结束
                    return finish(sessionId, history, userContent, true, null, content, start,
                            new LlmStreamService.Usage(promptTokens, completionTokens));
                }
                if (round == toolMaxRounds) {
                    // 达到轮次上限仍要调工具：不再执行，已流出的文字即最终内容
                    log.warn("工具调用轮次达上限未收尾作答 sessionId={} agentId={} rounds={}",
                            sessionId, agent.getId(), toolMaxRounds);
                    return finish(sessionId, history, userContent, true, null, content, start,
                            new LlmStreamService.Usage(promptTokens, completionTokens));
                }

                // 执行本轮全部工具调用，结果回传下一轮
                loopMessages.add(new LlmStreamService.ToolCallTurn(turnText.toString(), outcome.toolCalls()));
                for (LlmStreamService.ToolInvocation invocation : outcome.toolCalls()) {
                    appendToolResult(loopMessages, dispatch, invocation, sessionId, listener);
                }
                if (round == toolMaxRounds - 1) {
                    // 下一轮是最后一轮：在最后的工具结果里附带收尾提示，逼出纯文字回答
                    LlmStreamService.ToolResultTurn last =
                            (LlmStreamService.ToolResultTurn) loopMessages.get(loopMessages.size() - 1);
                    loopMessages.set(loopMessages.size() - 1, new LlmStreamService.ToolResultTurn(
                            last.toolCallId(),
                            last.content() + "\n（系统提示：已接近工具调用轮次上限，请基于已获得的信息直接给出最终回答，不要再调用工具）"));
                }
            }
        } catch (StreamAbortedException e) {
            log.info("工具对话流被客户端中止 sessionId={} 已生成{}字符", sessionId, content.length());
            return finish(sessionId, history, userContent, false, null, content, start, null);
        } catch (LlmApiException e) {
            log.warn("工具对话流失败 sessionId={} provider={} 原因={}", sessionId, provider.getName(), e.getMessage());
            return finish(sessionId, history, userContent, false,
                    ProviderHttpService.describeFailure(e), content, start, null);
        } catch (BizException e) {
            // 熔断开启、MCP 工具失败（报错不降级）等
            log.warn("工具对话失败 sessionId={} agentId={} 原因={}", sessionId, agent.getId(), e.getMessage());
            return finish(sessionId, history, userContent, false, e.getMessage(), content, start, null);
        }
        // 循环每条路径都有 return，这里防御不可达
        return finish(sessionId, history, userContent, true, null, content, start,
                new LlmStreamService.Usage(promptTokens, completionTokens));
    }

    /** 执行一次工具调用并把结果（或未知工具的纠错文本）追加进循环消息 */
    private void appendToolResult(List<LlmStreamService.LoopMessage> loopMessages,
            Map<String, ToolDispatch> dispatch, LlmStreamService.ToolInvocation invocation,
            Long sessionId, ChatStreamListener listener) {
        ToolDispatch target = dispatch.get(invocation.name());
        if (target == null) {
            // 模型幻觉出不存在的工具：把错误作为工具结果回传，让模型自我纠正
            log.warn("模型调用了未知工具 sessionId={} tool={}", sessionId, invocation.name());
            listener.onToolCall(new ToolCallEvent("", invocation.name(), invocation.arguments()));
            listener.onToolResult(new ToolResultEvent("", invocation.name(), false, "未知工具", 0));
            loopMessages.add(new LlmStreamService.ToolResultTurn(invocation.id(),
                    "错误：工具 " + invocation.name() + " 不存在，请修正后重试或直接回答"));
            return;
        }
        listener.onToolCall(new ToolCallEvent(target.serverName(), target.realToolName(), invocation.arguments()));
        long toolStart = System.currentTimeMillis();
        McpToolService.ToolCallResult result = mcpToolService.callTool(
                target.serverId(), target.realToolName(), invocation.arguments());
        int latency = (int) (System.currentTimeMillis() - toolStart);
        listener.onToolResult(new ToolResultEvent(target.serverName(), target.realToolName(),
                true, summarize(result.text()), latency));
        loopMessages.add(new LlmStreamService.ToolResultTurn(invocation.id(), result.text()));
    }

    /** 工具结果给前端的摘要（截断；完整结果只回传 LLM） */
    private static String summarize(String text) {
        return text.length() > 500 ? text.substring(0, 500) + "…(截断)" : text;
    }

    /** 落库 assistant 消息、更新 Redis 上下文、touch 会话活跃时间 */
    private ChatStreamResult finish(Long sessionId, List<ContextMessage> history, String userContent,
            boolean ok, String errorMessage, StringBuilder content, long start, LlmStreamService.Usage usage) {
        ChatStreamResult result = new ChatStreamResult();
        result.setOk(ok);
        result.setErrorMessage(errorMessage);
        result.setContent(content.toString());
        result.setLatencyMs((int) (System.currentTimeMillis() - start));
        result.setPromptTokens(usage == null ? 0 : usage.promptTokens());
        result.setCompletionTokens(usage == null ? 0 : usage.completionTokens());

        // 已生成内容落库（含中断的部分内容）；空内容不落库
        if (!content.isEmpty()) {
            ChatMessage assistant = new ChatMessage();
            assistant.setSessionId(sessionId);
            assistant.setRole(ROLE_ASSISTANT);
            assistant.setContent(content.toString());
            assistant.setPromptTokens(result.getPromptTokens());
            assistant.setCompletionTokens(result.getCompletionTokens());
            assistant.setLatencyMs(result.getLatencyMs());
            messageMapper.insert(assistant);
            result.setMessageId(assistant.getId());
        }

        // 成功才把本轮（user + assistant）写进上下文并续期
        if (ok && result.getMessageId() != null) {
            List<ContextMessage> updated = new ArrayList<>(history);
            updated.add(new ContextMessage(ROLE_USER, userContent));
            updated.add(new ContextMessage(ROLE_ASSISTANT, content.toString()));
            saveContext(sessionId, updated);
        }

        // 会话活跃时间（标题在首条消息时已更新）
        ChatSession touch = new ChatSession();
        touch.setId(sessionId);
        sessionMapper.updateById(touch);

        log.info("对话完成 sessionId={} ok={} tokens={}/{} 耗时={}ms 中断内容长度={}",
                sessionId, ok, result.getPromptTokens(), result.getCompletionTokens(),
                result.getLatencyMs(), content.length());
        return result;
    }

    // ---------- 上下文 ----------

    /** Redis 未命中时从 MySQL 重建（最近 maxHistory 条，时间正序） */
    private List<ContextMessage> loadHistory(Long sessionId, Long beforeMessageId) {
        @SuppressWarnings("unchecked")
        List<ContextMessage> cached = redisUtil.get(SESSION_KEY_PREFIX + sessionId);
        if (cached != null) {
            return cached;
        }
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .in(ChatMessage::getRole, ROLE_USER, ROLE_ASSISTANT)
                .lt(ChatMessage::getId, beforeMessageId)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + maxHistory);
        List<ChatMessage> rows = messageMapper.selectList(wrapper);
        List<ContextMessage> history = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {   // 反转成正序
            history.add(new ContextMessage(rows.get(i).getRole(), rows.get(i).getContent()));
        }
        return history;
    }

    /** 裁剪到 maxHistory 条后写 Redis 并续期 TTL */
    private void saveContext(Long sessionId, List<ContextMessage> messages) {
        List<ContextMessage> trimmed = messages.size() > maxHistory
                ? new ArrayList<>(messages.subList(messages.size() - maxHistory, messages.size()))
                : messages;
        redisUtil.set(SESSION_KEY_PREFIX + sessionId, trimmed,
                contextTtl.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 上下文 token 预算：模型配置了 maxContextTokens 用配置值，否则用默认兜底 */
    private int contextBudget(ModelConfig model) {
        return model.getMaxContextTokens() != null && model.getMaxContextTokens() > 0
                ? model.getMaxContextTokens()
                : defaultContextTokens;
    }

    /** 上下文 token 预算裁剪：超预算时从最旧开始丢弃，当前用户消息永远保留 */
    List<ContextMessage> trimByBudget(List<ContextMessage> history, int budget) {
        // 预算粗算即可（estimateTokens 同款启发式），不做精确分词
        int used = 0;
        for (ContextMessage message : history) {
            used += LlmStreamService.estimateTokens(message.getContent());
        }
        if (used <= budget) {
            return history;
        }
        List<ContextMessage> kept = new ArrayList<>(history);
        java.util.Iterator<ContextMessage> it = kept.iterator();
        while (it.hasNext() && used > budget && kept.size() > 1) {
            used -= LlmStreamService.estimateTokens(it.next().getContent());
            it.remove();
        }
        return kept;
    }

    // ---------- 内部 ----------

    private ChatSession requireSession(Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(ChatErrorCode.CHAT_SESSION_NOT_FOUND, "会话不存在，id=" + sessionId);
        }
        return session;
    }

    /** 首条消息把默认标题换成消息截断 */
    private void touchTitleAndSession(ChatSession session, List<ContextMessage> history, String content) {
        if (history.isEmpty() && DEFAULT_TITLE.equals(session.getTitle())) {
            String title = content.length() > TITLE_MAX ? content.substring(0, TITLE_MAX) : content;
            ChatSession update = new ChatSession();
            update.setId(session.getId());
            update.setTitle(title);
            sessionMapper.updateById(update);
        }
    }
}
