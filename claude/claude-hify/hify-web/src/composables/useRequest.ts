import { ref, type Ref } from 'vue'

export interface UseRequestResult<T, Args extends unknown[]> {
  /** 最近一次成功数据（未请求或失败时为 undefined） */
  data: Ref<T | undefined>
  loading: Ref<boolean>
  /** 最近一次错误（失败提示已由全局拦截器弹出，这里只记录状态供页面判断） */
  error: Ref<unknown>
  /** 发起请求（透传 API 参数）；失败时吞掉异常并返回 undefined，页面无需 try-catch */
  execute: (...args: Args) => Promise<T | undefined>
}

export interface UseRequestOptions {
  /** setup 时立即执行一次（默认 false，配合 onMounted 手动触发） */
  immediate?: boolean
}

/**
 * 请求状态管理：三态（data / loading / error）自动维护，免写 try-catch-finally 样板。
 *
 * 用法：
 *   const { data, loading, execute } = useRequest(getHealth)
 *   onMounted(execute)
 *
 *   // 带参数
 *   const { execute: save } = useRequest(saveProvider)
 *   await save(formData)
 */
export function useRequest<T, Args extends unknown[] = []>(
  api: (...args: Args) => Promise<T>,
  { immediate = false }: UseRequestOptions = {},
): UseRequestResult<T, Args> {
  const data = ref<T>() as Ref<T | undefined>
  const loading = ref(false)
  const error = ref<unknown>()

  async function execute(...args: Args): Promise<T | undefined> {
    loading.value = true
    error.value = undefined
    try {
      data.value = await api(...args)
      return data.value
    } catch (e) {
      error.value = e
      return undefined
    } finally {
      loading.value = false
    }
  }

  if (immediate) {
    execute()
  }

  return { data, loading, error, execute }
}
