import { marked, type Tokens } from 'marked'

/**
 * Markdown → HTML（AI 消息气泡渲染）。
 *
 * 安全策略：LLM 输出不可信（提示词注入可能让模型吐出 HTML/script）——
 * 1. 原始 HTML 一律转义成纯文本展示（代码块不受影响：marked 会对 code 内容自行转义）
 * 2. 链接只放行 http / https / mailto / 相对地址，新窗口打开
 *
 * 性能：流式期间每个 token 都会重渲染整条消息列表，v-html 每次都调本函数——
 * 按「内容字符串」做 Map 缓存，历史消息命中缓存，只有流中的尾部消息真正解析。
 */

const SAFE_URL_PATTERN = /^(https?:|mailto:|\/|#|\.\/)/i

function escapeHtml(text: string): string {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

marked.use({
  renderer: {
    // 原始 HTML（块级/行内）→ 转义成文本
    html(token: Tokens.HTML | Tokens.Tag) {
      return escapeHtml(token.text)
    },
    link(token: Tokens.Link) {
      const href = SAFE_URL_PATTERN.test(token.href ?? '') ? token.href : '#'
      const title = token.title ? ` title="${escapeHtml(token.title)}"` : ''
      const renderer = this as unknown as { parser?: { parseInline: (t: Tokens.Generic[]) => string } }
      const label = renderer.parser && token.tokens
        ? renderer.parser.parseInline(token.tokens)
        : escapeHtml(token.text ?? '')
      return `<a href="${escapeHtml(href)}"${title} target="_blank" rel="noopener noreferrer">${label}</a>`
    },
  },
})

const cache = new Map<string, string>()

export function renderMarkdown(md: string): string {
  if (!md) {
    return ''
  }
  const hit = cache.get(md)
  if (hit !== undefined) {
    return hit
  }
  const html = marked.parse(md, { breaks: true, gfm: true, async: false }) as string
  if (cache.size > 300) {
    cache.clear()   // 简单防膨胀：超量整体清空（历史消息很快重新填回）
  }
  cache.set(md, html)
  return html
}
