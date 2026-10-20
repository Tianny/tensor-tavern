import type { PageQuery, PageResult } from './api'

/**
 * HifyTable 列配置。
 * 普通列只给 label + prop；自定义单元格给 slot（同名作用域插槽，
 * 作用域为 el-table-column 的 scope：{ row, $index, column }）。
 */
export interface HifyTableColumn {
  label: string
  /** 取值字段（留空时该列必须提供 slot） */
  prop?: string
  width?: number | string
  minWidth?: number | string
  /** 自定义单元格：内容渲染到同名作用域插槽 */
  slot?: string
  align?: 'left' | 'center' | 'right'
  fixed?: 'left' | 'right'
  /** 内容超出省略并悬浮提示（纯文本列建议开启） */
  showOverflowTooltip?: boolean
}

/** HifyTable 数据源 API 签名：分页参数 + 任意查询条件，返回分页结果 */
export type HifyTableApi<T> = (params: PageQuery & Record<string, unknown>) => Promise<PageResult<T>>

/**
 * HifyFormDialog 提交回调。
 * 成功调 done()：结束 loading 并关闭弹窗；失败调 done(false)：仅结束 loading（弹窗保留）。
 */
export type HifyFormDialogDone = (success?: boolean) => void
