import { get } from '@/utils/request'

/** 健康检查：GET /api/v1/health */
export function getHealth(): Promise<string> {
  return get<string>('/v1/health')
}
