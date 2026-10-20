/** 后端统一响应 Result<T> */
export interface Result<T> {
  code: number
  message: string
  data: T
}

/**
 * 分页响应 PageResult<T>。
 * 后端契约为 Result<PageResult<T>>，axios 拦截器已解包 Result，
 * 页面层拿到的就是本结构（对应后端 com.hify.common.dto.PageResult）。
 */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

/** 分页请求参数（page 从 1 开始；pageSize 默认 20，最大 100） */
export interface PageQuery {
  page: number
  pageSize: number
}
