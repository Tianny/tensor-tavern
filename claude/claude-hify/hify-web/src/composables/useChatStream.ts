import { ref } from 'vue'

/**
 * 流式对话 composable：原生 fetch + ReadableStream 消费 SSE。
 * 不走 axios（XHR 会整体缓冲响应，且 Result 拦截器不适合流）；不用 EventSource（只支持 GET）。
 *
 * 后端事件协议（载荷均为单行 JSON）：
 *   event: token       data: {"d":"正文增量"}
 *   event: reasoning   data: {"d":"思考流增量（qwen reasoning_content / claude thinking）"}
 *   event: tool_call   data: {"server","tool","arguments"}          模型决定调用某个 MCP 工具
 *   event: tool_result data: {"server","tool","ok","result","latencyMs"}   工具执行完成
 *   event: done        data: {"messageId","promptTokens","completionTokens","latencyMs"}
 *   event: error       data: {"message","messageId"}   messageId 有值=部分内容已落库
 * 同步段校验失败时后端回普通 Result JSON（Content-Type 区分），走 onError。
 */

export interface ChatStreamDone {
  messageId: number
  promptTokens: number
  completionTokens: number
  latencyMs: number
}

export interface ChatStreamError {
  message: string
  /** 部分内容已落库的消息 id，0=无内容 */
  messageId: number
}

/** 模型决定调用某个 MCP 工具（function calling 的工具决策） */
export interface ChatToolCall {
  server: string
  tool: string
  arguments: string
}

/** 工具执行完成（result 为摘要文本） */
export interface ChatToolResult {
  server: string
  tool: string
  ok: boolean
  result: string
  latencyMs: number
}

export interface ChatStreamCallbacks {
  onToken: (delta: string) => void
  onDone: (meta: ChatStreamDone) => void
  onError: (error: ChatStreamError) => void
  /** 思考流增量（可选，思考型模型正文前触发） */
  onReasoning?: (delta: string) => void
  /** 工具调用事件（可选，绑定了 MCP 工具的 Agent 触发） */
  onToolCall?: (call: ChatToolCall) => void
  /** 工具执行结果事件（可选） */
  onToolResult?: (result: ChatToolResult) => void
}

export function useChatStream() {
  const streaming = ref(false)
  let controller: AbortController | null = null

  /** 发送一条消息并消费流。resolve 于流正常结束（含 error 事件），reject 只在请求建立失败 */
  async function send(sessionId: number, content: string, cb: ChatStreamCallbacks): Promise<void> {
    if (streaming.value) {
      return
    }
    streaming.value = true
    controller = new AbortController()
    let frameEvent = ''      // 当前 SSE 帧的事件名
    let frameData: string[] = []   // 当前帧的多行 data（按规范用 \n 连接）
    let streamTerminated = false
    try {
      const resp = await fetch(`/api/v1/sessions/${sessionId}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content }),
        signal: controller.signal,
      })
      // 同步段失败：普通 Result JSON（全局异常处理器），code != 200
      const contentType = resp.headers.get('content-type') ?? ''
      if (contentType.includes('application/json')) {
        const result = (await resp.json()) as { code: number; message: string }
        cb.onError({ message: result.message || '请求失败', messageId: 0 })
        streamTerminated = true
        return
      }
      if (!resp.ok || !resp.body) {
        cb.onError({ message: `请求失败（${resp.status}）`, messageId: 0 })
        streamTerminated = true
        return
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      const dispatchFrame = () => {
        const data = frameData.join('\n')
        const event = frameEvent || 'message'
        frameEvent = ''
        frameData = []
        if (!data) {
          return
        }
        let payload: Record<string, unknown>
        try {
          payload = JSON.parse(data) as Record<string, unknown>
        } catch {
          return   // 非 JSON 帧忽略（规范里 done 无载荷的情况不存在，防御）
        }
        if (event === 'token') {
          cb.onToken(String(payload.d ?? ''))
        } else if (event === 'reasoning') {
          cb.onReasoning?.(String(payload.d ?? ''))
        } else if (event === 'tool_call') {
          cb.onToolCall?.({
            server: String(payload.server ?? ''),
            tool: String(payload.tool ?? ''),
            arguments: String(payload.arguments ?? ''),
          })
        } else if (event === 'tool_result') {
          cb.onToolResult?.({
            server: String(payload.server ?? ''),
            tool: String(payload.tool ?? ''),
            ok: payload.ok !== false,
            result: String(payload.result ?? ''),
            latencyMs: Number(payload.latencyMs ?? 0),
          })
        } else if (event === 'done') {
          streamTerminated = true
          cb.onDone({
            messageId: Number(payload.messageId ?? 0),
            promptTokens: Number(payload.promptTokens ?? 0),
            completionTokens: Number(payload.completionTokens ?? 0),
            latencyMs: Number(payload.latencyMs ?? 0),
          })
        } else if (event === 'error') {
          streamTerminated = true
          cb.onError({
            message: String(payload.message ?? '对话失败'),
            messageId: Number(payload.messageId ?? 0),
          })
        }
      }

      const consumeChunk = (chunk: string) => {
        buffer += chunk
        // SSE 帧以空行分隔；行内 key: value
        let separator = buffer.indexOf('\n\n')
        while (separator !== -1) {
          const frame = buffer.slice(0, separator)
          buffer = buffer.slice(separator + 2)
          for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) {
              frameEvent = line.slice(6).trim()
            } else if (line.startsWith('data:')) {
              frameData.push(line.slice(5).trim())
            }
          }
          dispatchFrame()
          separator = buffer.indexOf('\n\n')
        }
      }

      // eslint-disable-next-line no-constant-condition
      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }
        consumeChunk(decoder.decode(value, { stream: true }))
      }
      // 流关闭但没收到 done/error（连接被掐断）
      if (!streamTerminated) {
        cb.onError({ message: '连接中断', messageId: 0 })
      }
    } catch (e) {
      // 用户主动停止（abort）不算错误：已生成部分已由后端落库
      if (!(e instanceof DOMException && e.name === 'AbortError')) {
        cb.onError({ message: e instanceof Error ? e.message : '网络异常', messageId: 0 })
      }
    } finally {
      streaming.value = false
      controller = null
    }
  }

  /** 停止生成：中断 fetch，后端感知断开后停止读上游并保留已生成内容 */
  function stop() {
    controller?.abort()
  }

  return { streaming, send, stop }
}
