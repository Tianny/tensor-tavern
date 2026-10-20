import { ElMessage } from 'element-plus'

/**
 * 统一通知封装：项目里弹消息一律走这三个方法（含 request.ts 拦截器），
 * 时长和样式只在这一处调整。样式本身跟随 tokens.css 主题。
 */

/** 成功短、警告中、错误长（错误信息需要阅读时间） */
const DURATION = { success: 2000, warning: 3000, error: 4000 } as const

export function notifySuccess(message: string): void {
  ElMessage({ type: 'success', message, duration: DURATION.success, grouping: true })
}

export function notifyWarning(message: string): void {
  ElMessage({ type: 'warning', message, duration: DURATION.warning, grouping: true, showClose: true })
}

export function notifyError(message: string): void {
  ElMessage({ type: 'error', message, duration: DURATION.error, grouping: true, showClose: true })
}
