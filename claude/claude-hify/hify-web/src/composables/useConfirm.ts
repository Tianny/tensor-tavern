import { ElMessageBox } from 'element-plus'

import { notifySuccess } from '@/utils/notify'

export interface ConfirmOptions {
  /** 弹窗标题，默认「删除确认」 */
  title?: string
  /** 确认按钮文案，默认「删除」（红色危险按钮） */
  confirmButtonText?: string
  /** 成功提示文案，默认「删除成功」；传空字符串则不提示 */
  successMessage?: string
}

/**
 * 删除确认全流程：ElMessageBox 确认 → 调 API → 成功提示，一行完成。
 *
 * 用法（点击删除时）：
 *   await useConfirm(`确认删除「${row.name}」？`, () => deleteProvider(row.id))
 *   tableRef.value?.refresh()
 *
 * 用户取消：静默返回 undefined；API 失败：错误提示已由全局拦截器弹出，同样返回 undefined。
 * 成功返回 API 结果（通常为后端 data 字段）。
 */
export async function useConfirm<T = unknown>(
  message: string,
  api: () => Promise<T>,
  options: ConfirmOptions = {},
): Promise<T | undefined> {
  const { title = '删除确认', confirmButtonText = '删除', successMessage = '删除成功' } = options

  try {
    await ElMessageBox.confirm(message, title, {
      type: 'warning',
      confirmButtonText,
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
    })
  } catch {
    return undefined // 用户取消，不做任何提示
  }

  try {
    const result = await api()
    if (successMessage) {
      notifySuccess(successMessage)
    }
    return result
  } catch {
    return undefined // 失败提示由 request 拦截器统一弹出
  }
}
