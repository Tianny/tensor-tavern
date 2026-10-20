import axios from 'axios'

import { notifyError } from '@/utils/notify'
import type { Result } from '@/types/api'

/**
 * axios 实例：baseURL 为 /api，业务路径从 /v1 开始写（如 /v1/providers）。
 * 开发环境由 Vite 代理转发到 localhost:8080，生产环境由 Nginx 反代。
 */
const instance = axios.create({
  baseURL: '/api',
  timeout: 60_000,
})

// 响应拦截器：code === 200 自动解包，直接把 data 字段作为成功值返回；
// 非 200 用 ElMessage 提示 message 并 reject
instance.interceptors.response.use(
  (response) => {
    const result = response.data as Result<unknown>
    if (result && result.code !== 200) {
      const message = result.message || '请求失败'
      notifyError(message)
      return Promise.reject(new Error(message))
    }
    return result.data as unknown as typeof response
  },
  (error) => {
    notifyError(error.message || '网络异常')
    return Promise.reject(error)
  },
)

export function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return instance.get(url, { params }) as unknown as Promise<T>
}

export function post<T>(url: string, body?: unknown): Promise<T> {
  return instance.post(url, body) as unknown as Promise<T>
}

export function put<T>(url: string, body?: unknown): Promise<T> {
  return instance.put(url, body) as unknown as Promise<T>
}

export function del<T>(url: string): Promise<T> {
  return instance.delete(url) as unknown as Promise<T>
}

export default instance
