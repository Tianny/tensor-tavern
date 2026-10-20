<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ArrowDown, Box, ChatDotRound, Delete, Plus, Promotion } from '@element-plus/icons-vue'

import { useConfirm } from '@/composables/useConfirm'
import { useChatStream } from '@/composables/useChatStream'
import { notifySuccess } from '@/utils/notify'
import { renderMarkdown } from '@/utils/markdown'
import {
  createSession,
  deleteSession,
  listMessages,
  listSessions,
  type ChatMessage,
  type ChatSession,
} from '@/api/chat'
import { listAgents, type Agent } from '@/api/agent'
import { listWorkflows, type Workflow } from '@/api/workflow'

/**
 * 对话页：左侧 Agent + 会话列表，右侧消息流。
 * 流式链路见 useChatStream（fetch + SSE）；失败/中断的气泡给「重试」重发同一句。
 */

// ---------------- UI 消息模型 ----------------

/** 一次 MCP 工具调用（function calling）：流式期间由 tool_call / tool_result 事件填充 */
interface UiToolCall {
  server: string
  tool: string
  arguments: string
  status: 'running' | 'done' | 'failed'
  result?: string
  latencyMs?: number
  /** 卡片展开态（执行中默认展开） */
  open: boolean
}

interface UiMessage {
  /** 0 = 本地乐观消息（尚未回带 id） */
  id: number
  role: 'user' | 'assistant'
  content: string
  /** 思考流（qwen reasoning_content / claude thinking），只展示不落库 */
  reasoning?: string
  /** 工具调用卡片（只随流展示，不落库） */
  toolCalls?: UiToolCall[]
  status: 'done' | 'streaming' | 'error' | 'stopped'
  errorMessage?: string
  promptTokens?: number
  completionTokens?: number
  latencyMs?: number
  /** 重试用：失败气泡对应的用户消息内容 */
  retryContent?: string
}

const PAGE_SIZE = 20

// ---------------- Agent 与会话 ----------------

const agents = ref<Agent[]>([])
const workflows = ref<Workflow[]>([])
const agentId = ref<number | null>(null)
const workflowId = ref<number | null>(null)
const sessions = ref<ChatSession[]>([])
const sessionId = ref<number | null>(null)
const messages = ref<UiMessage[]>([])
const loadingMessages = ref(false)
const hasMore = ref(false)

const activeAgent = computed(() => agents.value.find((a) => a.id === agentId.value) ?? null)
const activeWorkflow = computed(() => workflows.value.find((w) => w.id === workflowId.value) ?? null)
const activeSession = computed(() => sessions.value.find((s) => s.id === sessionId.value) ?? null)
/** 当前对话对象名（Agent 或工作流），头部 chip 与欢迎语共用 */
const activeTargetName = computed(() => activeAgent.value?.name ?? activeWorkflow.value?.name ?? null)

/** 顶部选择器的统一值：a-{agentId} / w-{workflowId}，Agent 与工作流互斥 */
const targetValue = computed({
  get: () =>
    workflowId.value !== null ? `w-${workflowId.value}`
      : agentId.value !== null ? `a-${agentId.value}` : '',
  set: (v: string) => {
    if (v.startsWith('w-')) {
      workflowId.value = Number(v.slice(2))
      agentId.value = null
    } else {
      agentId.value = Number(v.slice(2))
      workflowId.value = null
    }
  },
})

async function loadAgents() {
  const resp = await listAgents({ page: 1, pageSize: 100 })
  agents.value = resp.list.filter((a) => a.enabled === 1)
  const wfList = await listWorkflows()
  workflows.value = wfList.filter((w) => w.enabled === 1)
  if (agentId.value === null && workflowId.value === null) {
    if (agents.value.length) {
      agentId.value = agents.value[0].id
    } else if (workflows.value.length) {
      workflowId.value = workflows.value[0].id
    }
  }
}

async function loadSessions() {
  if (agentId.value === null && workflowId.value === null) {
    sessions.value = []
    return
  }
  sessions.value = await listSessions(agentId.value ?? undefined, workflowId.value ?? undefined)
}

watch([agentId, workflowId], () => {
  sessionId.value = null
  messages.value = []
  loadSessions()
})

async function handleNewSession() {
  if (agentId.value === null && workflowId.value === null) {
    return
  }
  const session = await createSession(
    agentId.value !== null ? { agentId: agentId.value } : { workflowId: workflowId.value! },
  )
  sessions.value.unshift(session)
  await selectSession(session.id)
}

async function selectSession(id: number) {
  sessionId.value = id
  loadingMessages.value = true
  try {
    const list = await listMessages(id, undefined, PAGE_SIZE)
    messages.value = list.map(toUiMessage)
    hasMore.value = list.length === PAGE_SIZE
    scrollToBottom()
  } finally {
    loadingMessages.value = false
  }
}

async function handleDeleteSession(session: ChatSession) {
  await useConfirm(`删除会话「${session.title || '新对话'}」？消息记录将一并移除入口。`, () =>
    deleteSession(session.id),
  )
  sessions.value = sessions.value.filter((s) => s.id !== session.id)
  if (sessionId.value === session.id) {
    sessionId.value = null
    messages.value = []
  }
}

function toUiMessage(m: ChatMessage): UiMessage {
  return {
    id: m.id,
    role: m.role,
    content: m.content,
    status: 'done',
    promptTokens: m.promptTokens,
    completionTokens: m.completionTokens,
    latencyMs: m.latencyMs,
  }
}

// ---------------- 历史加载（向上翻页） ----------------

async function loadOlder() {
  if (sessionId.value === null || loadingMessages.value || !hasMore.value) {
    return
  }
  const oldest = messages.value.find((m) => m.id > 0)?.id
  if (!oldest) {
    return
  }
  loadingMessages.value = true
  try {
    const list = await listMessages(sessionId.value, oldest, PAGE_SIZE)
    messages.value = [...list.map(toUiMessage), ...messages.value]
    hasMore.value = list.length === PAGE_SIZE
  } finally {
    loadingMessages.value = false
  }
}

// ---------------- 发送 / 流式接收 ----------------

const { streaming, send, stop } = useChatStream()
const input = ref('')

const messageListRef = ref<HTMLElement>()
const inputRef = ref<HTMLInputElement>()

async function handleSend() {
  const content = input.value.trim()
  if (!content || streaming.value || sessionId.value === null) {
    return
  }
  input.value = ''
  startStream(content)
}

async function startStream(content: string) {
  if (sessionId.value === null) {
    return
  }
  messages.value.push({ id: 0, role: 'user', content, status: 'done' })
  // 必须用 reactive 包装：直接 push 普通对象后，循环里对裸对象的赋值
  // 不会触发 Vue 重渲染（表现为打字机失效、内容憋到结束一次性出现）
  const bubble = reactive<UiMessage>({ id: 0, role: 'assistant', content: '', status: 'streaming' })
  messages.value.push(bubble)
  scrollToBottom()

  await send(sessionId.value, content, {
    onToken: (delta) => {
      bubble.content += delta
      scrollToBottom()
    },
    onReasoning: (delta) => {
      bubble.reasoning = (bubble.reasoning ?? '') + delta
      scrollToBottom()
    },
    onToolCall: (call) => {
      if (!bubble.toolCalls) {
        bubble.toolCalls = []
      }
      bubble.toolCalls.push({
        server: call.server,
        tool: call.tool,
        arguments: call.arguments,
        status: 'running',
        open: true,
      })
      scrollToBottom()
    },
    onToolResult: (result) => {
      // 配对同名的进行中卡片（同轮可能并发多次同名调用，从最新的配起）
      const list = bubble.toolCalls ?? []
      for (let i = list.length - 1; i >= 0; i--) {
        if (list[i].tool === result.tool && list[i].status === 'running') {
          list[i].status = result.ok ? 'done' : 'failed'
          list[i].result = result.result
          list[i].latencyMs = result.latencyMs
          break
        }
      }
      scrollToBottom()
    },
    onDone: (meta) => {
      bubble.status = 'done'
      bubble.id = meta.messageId
      bubble.promptTokens = meta.promptTokens
      bubble.completionTokens = meta.completionTokens
      bubble.latencyMs = meta.latencyMs
      loadSessions()   // 刷新标题与排序
    },
    onError: (error) => {
      // 失败/中断统一红色错误态；已生成的部分内容保留在气泡里展示
      bubble.status = 'error'
      bubble.errorMessage = error.message
      if (error.messageId) {
        bubble.id = error.messageId
      }
      bubble.retryContent = content
    },
  })

  // 用户点「停止生成」：abort 不触发 error 回调，气泡不能停在 streaming 态
  if (bubble.status === 'streaming') {
    bubble.status = 'stopped'
    bubble.errorMessage = '已停止生成'
    bubble.retryContent = content
  }
}

async function handleRetry(message: UiMessage) {
  if (message.retryContent === undefined || streaming.value) {
    return
  }
  startStream(message.retryContent)
}

const canSend = computed(() => !!input.value.trim() && !streaming.value && sessionId.value !== null)

function scrollToBottom() {
  nextTick(() => {
    const el = messageListRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

function formatTime(iso: string): string {
  return iso ? iso.replace('T', ' ').slice(0, 16) : ''
}

function onInputKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

onMounted(async () => {
  await loadAgents()
  inputRef.value?.focus()
})
</script>

<template>
  <div class="chat-page">
    <!-- 左侧：Agent 选择 + 会话列表 -->
    <aside class="chat-side">
      <div class="side-head">
        <el-select
          v-model="targetValue"
          placeholder="选择 Agent 或工作流"
          size="default"
          style="width: 100%"
        >
          <el-option-group label="Agent">
            <el-option v-for="a in agents" :key="a.id" :label="a.name" :value="`a-${a.id}`" />
          </el-option-group>
          <el-option-group v-if="workflows.length" label="工作流">
            <el-option v-for="w in workflows" :key="w.id" :label="w.name" :value="`w-${w.id}`" />
          </el-option-group>
        </el-select>
        <el-button
          type="primary"
          class="new-session-btn"
          :disabled="targetValue === ''"
          @click="handleNewSession"
        >
          <el-icon><Plus /></el-icon>
          新建会话
        </el-button>
      </div>
      <div v-if="agents.length === 0 && workflows.length === 0" class="side-empty">
        暂无启用的 Agent 或工作流，先去
        <router-link to="/agent" class="side-link">Agent 管理</router-link>
        或
        <router-link to="/workflow" class="side-link">工作流</router-link>
        创建一个
      </div>
      <div v-else class="session-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="session-item"
          :class="{ 'is-active': s.id === sessionId }"
          @click="selectSession(s.id)"
        >
          <div class="session-title">{{ s.title || '新对话' }}</div>
          <div class="session-time">{{ formatTime(s.updatedAt) }}</div>
          <el-icon class="session-delete" @click.stop="handleDeleteSession(s)"><Delete /></el-icon>
        </div>
      </div>
    </aside>

    <!-- 右侧：消息流 + 输入 -->
    <section class="chat-main">
      <header class="chat-header">
        <div class="chat-header-info">
          <h2 class="chat-title">{{ activeSession?.title || '对话' }}</h2>
          <span v-if="activeAgent" class="chat-agent">{{ activeAgent.name }}</span>
          <span v-else-if="activeWorkflow" class="chat-agent chat-agent-workflow">
            工作流 · {{ activeWorkflow.name }}
          </span>
        </div>
      </header>

      <div ref="messageListRef" v-loading="loadingMessages" class="message-list">
        <button v-if="hasMore && messages.length" class="load-older" @click="loadOlder">
          加载更早消息
        </button>

        <div v-if="!messages.length && sessionId" class="chat-welcome">
          <el-icon class="welcome-icon"><ChatDotRound /></el-icon>
          <p>和 {{ activeTargetName ?? 'Agent' }} 开始对话吧</p>
        </div>

        <div
          v-for="(m, i) in messages"
          :key="i"
          class="bubble-row"
          :class="m.role === 'user' ? 'is-user' : 'is-assistant'"
        >
          <div class="bubble" :class="{ 'is-error': m.status === 'error' }">
            <!-- 思考流：思考型模型正文前先流出，灰色小字 -->
            <div v-if="m.reasoning" class="bubble-reasoning">
              <span class="reasoning-label">{{ m.status === 'streaming' && !m.content ? '思考中' : '思考过程' }}</span>
              <div class="reasoning-text">{{ m.reasoning }}</div>
            </div>
            <!-- 工具调用卡片：MCP 工具（function calling），可折叠看参数与结果 -->
            <div v-if="m.toolCalls?.length" class="bubble-tools">
              <div
                v-for="(tc, ti) in m.toolCalls"
                :key="ti"
                class="tool-card"
                :class="{ 'is-failed': tc.status === 'failed' }"
              >
                <button class="tool-head" @click="tc.open = !tc.open">
                  <el-icon class="tool-icon"><Box /></el-icon>
                  <span class="tool-name">{{ tc.server ? `${tc.server} · ` : '' }}{{ tc.tool }}</span>
                  <span class="tool-status" :class="`is-${tc.status}`">
                    {{ tc.status === 'running' ? '执行中…' : tc.status === 'failed' ? '失败' : `${tc.latencyMs ?? 0}ms` }}
                  </span>
                  <el-icon class="tool-arrow" :class="{ 'is-open': tc.open }"><ArrowDown /></el-icon>
                </button>
                <div v-if="tc.open" class="tool-body">
                  <div class="tool-args">{{ tc.arguments }}</div>
                  <div v-if="tc.result" class="tool-result">{{ tc.result }}</div>
                </div>
              </div>
            </div>
            <div class="bubble-content">
              <!-- 正文与思考流都未到时：三点加载动画；有内容后：文本 + 流式光标 -->
              <span v-if="m.status === 'streaming' && !m.content && !m.reasoning" class="typing-dots">
                <i /><i /><i />
              </span>
              <template v-else>
                <!-- AI 消息渲染 Markdown（代码块等宽字体）；用户消息保持纯文本 -->
                <div v-if="m.role === 'assistant'" class="bubble-md" v-html="renderMarkdown(m.content)" />
                <template v-else>{{ m.content }}</template>
                <span v-if="m.status === 'streaming' && m.content" class="cursor" />
              </template>
            </div>
            <div v-if="m.status === 'error'" class="bubble-error">
              {{ m.errorMessage }}
              <el-button link type="primary" size="small" @click="handleRetry(m)">重试</el-button>
            </div>
            <div v-else-if="m.status === 'stopped'" class="bubble-meta">
              {{ m.errorMessage }}
              <el-button link type="primary" size="small" @click="handleRetry(m)">重试</el-button>
            </div>
            <div v-else-if="m.role === 'assistant' && m.status === 'done'" class="bubble-meta">
              {{ m.completionTokens }} tokens · {{ m.latencyMs }}ms
            </div>
          </div>
        </div>
      </div>

      <footer class="chat-input">
        <el-input
          ref="inputRef"
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          :disabled="sessionId === null"
          @keydown="onInputKeydown"
        />
        <div class="input-actions">
          <!-- 流式期间发送保持禁用态（时间线：发送按钮不可点击→恢复），停止生成并行展示 -->
          <el-button type="primary" :disabled="!canSend" class="send-btn" @click="handleSend">
            <el-icon><Promotion /></el-icon>
            发送
          </el-button>
          <el-button v-if="streaming" @click="stop">停止生成</el-button>
        </div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  height: 100%;
  background: var(--hify-bg-page);
}

/* ---------- 左侧会话栏 ---------- */

.chat-side {
  display: flex;
  flex-direction: column;
  width: 260px;
  flex-shrink: 0;
  background: var(--hify-bg-surface);
  border-right: 1px solid var(--hify-border);
}

.side-head {
  display: flex;
  flex-direction: column;
  gap: var(--hify-space-2);
  padding: var(--hify-space-4);
}

/* 页面关键 CTA：hover 品牌光晕 */
.new-session-btn.el-button--primary:not(:disabled):hover {
  box-shadow: var(--hify-shadow-primary);
}

.side-empty {
  padding: var(--hify-space-6) var(--hify-space-4);
  font-size: var(--hify-text-sm);
  color: var(--hify-text-3);
  line-height: 1.8;
}

.side-link {
  color: var(--hify-primary-500);
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--hify-space-2);
}

.session-item {
  position: relative;
  padding: var(--hify-space-3);
  border-radius: var(--hify-radius-md);
  cursor: pointer;
  transition: background var(--hify-transition-color);
}

.session-item:hover {
  background: var(--hify-bg-subtle);
}

.session-item.is-active {
  background: var(--hify-primary-50);
  box-shadow: inset 2px 0 0 var(--hify-primary-500);
}

.session-title {
  font-size: var(--hify-text-sm);
  font-weight: var(--hify-weight-medium);
  color: var(--hify-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding-right: var(--hify-space-5);
}

.session-time {
  margin-top: var(--hify-space-1);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
  font-family: var(--hify-font-mono);
}

.session-delete {
  position: absolute;
  right: var(--hify-space-2);
  top: var(--hify-space-3);
  color: var(--hify-text-3);
  opacity: 0;
  transition: opacity var(--hify-transition-color);
}

.session-item:hover .session-delete {
  opacity: 1;
}

.session-delete:hover {
  color: var(--hify-danger);
}

/* ---------- 右侧消息区 ---------- */

.chat-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.chat-header {
  display: flex;
  align-items: center;
  padding: var(--hify-space-4) var(--hify-space-6);
  background: var(--hify-bg-surface);
  border-bottom: 1px solid var(--hify-border);
}

.chat-header-info {
  display: flex;
  align-items: baseline;
  gap: var(--hify-space-3);
  min-width: 0;
}

.chat-title {
  margin: 0;
  font-size: var(--hify-text-base);
  font-weight: var(--hify-weight-semibold);
  color: var(--hify-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-agent {
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
  flex-shrink: 0;
}

/* 工作流会话的头部 chip：薄荷绿辅色区分 Agent 直答 */
.chat-agent-workflow {
  color: var(--hify-accent-500);
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: var(--hify-space-6) var(--hify-space-6) var(--hify-space-4);
}

.load-older {
  display: block;
  margin: 0 auto var(--hify-space-4);
  padding: var(--hify-space-1) var(--hify-space-4);
  border: 1px solid var(--hify-border);
  border-radius: var(--hify-radius-full);
  background: var(--hify-bg-surface);
  color: var(--hify-text-3);
  font-size: var(--hify-text-xs);
  cursor: pointer;
  transition: all var(--hify-transition-color);
}

.load-older:hover {
  color: var(--hify-primary-500);
  border-color: var(--hify-primary-300);
}

.chat-welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--hify-text-3);
  font-size: var(--hify-text-sm);
}

.welcome-icon {
  font-size: 48px;
  color: var(--hify-primary-300);
  margin-bottom: var(--hify-space-3);
}

/* ---------- 气泡 ---------- */

.bubble-row {
  display: flex;
  margin-bottom: var(--hify-space-4);
}

.bubble-row.is-user {
  justify-content: flex-end;
}

.bubble-row.is-assistant {
  justify-content: flex-start;
}

.bubble {
  max-width: 72%;
  padding: var(--hify-space-3) var(--hify-space-4);
  border-radius: var(--hify-radius-lg);
  font-size: var(--hify-text-sm);
  line-height: 1.7;
}

.is-user .bubble {
  background: var(--hify-primary-500);
  color: #fff;
  border-bottom-right-radius: var(--hify-radius-sm);
}

.is-assistant .bubble {
  background: var(--hify-bg-surface);
  border: 1px solid var(--hify-border);
  color: var(--hify-text-1);
  border-bottom-left-radius: var(--hify-radius-sm);
}

.bubble.is-error {
  border-color: var(--hify-danger);
}

.bubble-content {
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- AI 气泡的 Markdown 渲染（v-html，样式经 :deep 穿透） ---------- */

.bubble-md {
  white-space: normal;
  word-break: break-word;
}

.bubble-md :deep(p) {
  margin: 0 0 var(--hify-space-2);
}

.bubble-md :deep(p:last-child) {
  margin-bottom: 0;
}

.bubble-md :deep(h1),
.bubble-md :deep(h2),
.bubble-md :deep(h3),
.bubble-md :deep(h4) {
  margin: var(--hify-space-4) 0 var(--hify-space-2);
  font-weight: var(--hify-weight-semibold);
  color: var(--hify-text-1);
}

.bubble-md :deep(h1) {
  font-size: var(--hify-text-lg);
}

.bubble-md :deep(h2) {
  font-size: var(--hify-text-base);
}

.bubble-md :deep(h3),
.bubble-md :deep(h4) {
  font-size: var(--hify-text-sm);
}

.bubble-md :deep(h1:first-child),
.bubble-md :deep(h2:first-child),
.bubble-md :deep(h3:first-child) {
  margin-top: 0;
}

.bubble-md :deep(ul),
.bubble-md :deep(ol) {
  margin: 0 0 var(--hify-space-2);
  padding-left: var(--hify-space-5);
}

.bubble-md :deep(li) {
  margin: var(--hify-space-1) 0;
}

.bubble-md :deep(a) {
  color: var(--hify-primary-500);
  text-decoration: none;
}

.bubble-md :deep(a:hover) {
  text-decoration: underline;
}

.bubble-md :deep(blockquote) {
  margin: 0 0 var(--hify-space-2);
  padding: var(--hify-space-1) var(--hify-space-3);
  border-left: 2px solid var(--hify-border);
  color: var(--hify-text-3);
}

.bubble-md :deep(table) {
  margin: 0 0 var(--hify-space-2);
  border-collapse: collapse;
}

.bubble-md :deep(th),
.bubble-md :deep(td) {
  padding: var(--hify-space-1) var(--hify-space-3);
  border: 1px solid var(--hify-border);
  font-size: var(--hify-text-sm);
}

.bubble-md :deep(th) {
  background: var(--hify-bg-subtle);
  font-weight: var(--hify-weight-medium);
}

/* 代码块：等宽字体 + 浅底 + 横向滚动 */
.bubble-md :deep(pre) {
  margin: 0 0 var(--hify-space-2);
  padding: var(--hify-space-3);
  background: var(--hify-bg-subtle);
  border: 1px solid var(--hify-border);
  border-radius: var(--hify-radius-md);
  overflow-x: auto;
  font-family: var(--hify-font-mono);
  font-size: var(--hify-text-sm);
  line-height: 1.6;
}

/* 块内 code 不叠加底色；行内 code 浅底胶囊 */
.bubble-md :deep(pre code) {
  background: none;
  padding: 0;
  border-radius: 0;
  font-family: var(--hify-font-mono);
}

.bubble-md :deep(code) {
  padding: 1px var(--hify-space-1);
  background: var(--hify-bg-subtle);
  border-radius: var(--hify-radius-sm);
  font-family: var(--hify-font-mono);
  font-size: var(--hify-text-sm);
}

.bubble-md :deep(hr) {
  margin: var(--hify-space-3) 0;
  border: none;
  border-top: 1px solid var(--hify-border);
}

/* ---------- 工具调用卡片（MCP 工具，薄荷绿辅色） ---------- */

.bubble-tools {
  display: flex;
  flex-direction: column;
  gap: var(--hify-space-2);
  margin-bottom: var(--hify-space-2);
}

.tool-card {
  border: 1px solid var(--hify-border);
  border-radius: var(--hify-radius-md);
  overflow: hidden;
}

.tool-card.is-failed {
  border-color: var(--hify-danger);
}

.tool-head {
  display: flex;
  align-items: center;
  gap: var(--hify-space-2);
  width: 100%;
  padding: var(--hify-space-2) var(--hify-space-3);
  background: var(--hify-bg-subtle);
  border: none;
  cursor: pointer;
  font-size: var(--hify-text-xs);
  transition: background var(--hify-transition-color);
}

.tool-head:hover {
  background: var(--hify-accent-50);
}

.tool-icon {
  color: var(--hify-accent-500);
  flex-shrink: 0;
}

.tool-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
  color: var(--hify-text-1);
}

.tool-status {
  flex-shrink: 0;
  font-family: var(--hify-font-mono);
  color: var(--hify-accent-500);
}

.tool-status.is-failed {
  color: var(--hify-danger);
}

.tool-status.is-running {
  color: var(--hify-text-3);
}

.tool-arrow {
  flex-shrink: 0;
  color: var(--hify-text-3);
  transition: transform var(--hify-transition-color);
}

.tool-arrow.is-open {
  transform: rotate(180deg);
}

.tool-body {
  padding: var(--hify-space-2) var(--hify-space-3);
  border-top: 1px solid var(--hify-border);
}

.tool-args {
  font-family: var(--hify-font-mono);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
  white-space: pre-wrap;
  word-break: break-word;
}

.tool-result {
  margin-top: var(--hify-space-2);
  padding-top: var(--hify-space-2);
  border-top: 1px dashed var(--hify-border);
  font-size: var(--hify-text-xs);
  line-height: 1.6;
  color: var(--hify-text-2);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 180px;
  overflow-y: auto;
}

/* 思考流：灰色小字块，弱化展示 */

.bubble-reasoning {
  margin-bottom: var(--hify-space-2);
  padding-left: var(--hify-space-3);
  border-left: 2px solid var(--hify-border);
}

.reasoning-label {
  display: block;
  margin-bottom: var(--hify-space-1);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
}

.reasoning-text {
  font-size: var(--hify-text-xs);
  line-height: 1.6;
  color: var(--hify-text-3);
  white-space: pre-wrap;
  word-break: break-word;
}

/* 首个 token 前的三点加载动画（打字中） */

.typing-dots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 1em;
}

.typing-dots i {
  width: 5px;
  height: 5px;
  border-radius: var(--hify-radius-full);
  background: var(--hify-primary-400);
  animation: typing-bounce 1s ease-in-out infinite;
}

.typing-dots i:nth-child(2) {
  animation-delay: 0.15s;
}

.typing-dots i:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes typing-bounce {
  0%, 60%, 100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  30% {
    transform: translateY(-3px);
    opacity: 1;
  }
}

/* 流式光标：主色闪烁 */

.cursor {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--hify-primary-500);
  animation: cursor-blink 0.8s steps(1) infinite;
}

@keyframes cursor-blink {
  50% {
    opacity: 0;
  }
}

.bubble-error {
  margin-top: var(--hify-space-2);
  font-size: var(--hify-text-xs);
  color: var(--hify-danger);
}

.bubble-meta {
  margin-top: var(--hify-space-2);
  font-size: var(--hify-text-xs);
  color: var(--hify-text-3);
  font-family: var(--hify-font-mono);
}

/* ---------- 输入区 ---------- */

.chat-input {
  display: flex;
  flex-direction: column;
  gap: var(--hify-space-2);
  padding: var(--hify-space-4) var(--hify-space-6) var(--hify-space-5);
  background: var(--hify-bg-surface);
  border-top: 1px solid var(--hify-border);
}

.input-actions {
  display: flex;
  justify-content: flex-end;
}

.send-btn.el-button--primary:not(:disabled):hover {
  box-shadow: var(--hify-shadow-primary);
}
</style>
