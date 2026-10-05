import { ref, type Ref } from 'vue'
import { JankApiError, errorMessage, isAbortError } from '../api/jankApi'

export interface JankQueryRegion<T> {
  data: Ref<T | null>
  loading: Ref<boolean>
  error: Ref<string | null>
  run: (loader: (signal: AbortSignal) => Promise<T>) => Promise<T | undefined>
  cancel: () => void
  reset: () => void
}

export function createJankQueryRegion<T>(): JankQueryRegion<T> {
  const data = ref<T | null>(null) as Ref<T | null>
  const loading = ref(false)
  const error = ref<string | null>(null)
  let requestToken = 0
  let controller: AbortController | null = null

  async function run(loader: (signal: AbortSignal) => Promise<T>): Promise<T | undefined> {
    const token = ++requestToken
    controller?.abort()
    controller = new AbortController()
    loading.value = true
    error.value = null
    try {
      const result = await loader(controller.signal)
      if (token !== requestToken) {
        return undefined
      }
      data.value = result
      return result
    } catch (requestError) {
      if (token === requestToken && !isAbortError(requestError)) {
        error.value = errorMessage(requestError)
      }
      return undefined
    } finally {
      if (token === requestToken) {
        loading.value = false
      }
    }
  }

  function cancel(): void {
    requestToken++
    controller?.abort()
    controller = null
    loading.value = false
  }

  function reset(): void {
    cancel()
    data.value = null
    error.value = null
  }

  return { data, loading, error, run, cancel, reset }
}

export interface JankCursorPage<T> {
  items: T[]
  nextCursor: string | null
  /** 首次响应的绝对时间窗，续页沿用。 */
  from?: string
  to?: string
}

export interface JankCursorQuery<T> {
  items: Ref<T[]>
  nextCursor: Ref<string | null>
  loading: Ref<boolean>
  loadingMore: Ref<boolean>
  error: Ref<string | null>
  appendError: Ref<string | null>
  /** 游标失败只能由显式重新查询恢复。 */
  cursorInvalid: Ref<boolean>
  range: Ref<{ from: string; to: string } | null>
  load: (loader: (cursor: undefined, signal: AbortSignal) => Promise<JankCursorPage<T>>) => Promise<void>
  loadMore: (loader: (cursor: string, signal: AbortSignal) => Promise<JankCursorPage<T>>) => Promise<void>
  cancel: () => void
  reset: () => void
}

export function createJankCursorQuery<T>(keyOf: (item: T) => string): JankCursorQuery<T> {
  const items = ref<T[]>([]) as Ref<T[]>
  const nextCursor = ref<string | null>(null)
  const loading = ref(false)
  const loadingMore = ref(false)
  const error = ref<string | null>(null)
  const appendError = ref<string | null>(null)
  /** 不透明游标和时间窗只保存在本次查询状态。 */
  const cursorInvalid = ref(false)
  const range = ref<{ from: string; to: string } | null>(null)
  let requestToken = 0
  let controller: AbortController | null = null

  async function load(
    loader: (cursor: undefined, signal: AbortSignal) => Promise<JankCursorPage<T>>,
  ): Promise<void> {
    const token = ++requestToken
    controller?.abort()
    controller = new AbortController()
    items.value = []
    nextCursor.value = null
    error.value = null
    appendError.value = null
    cursorInvalid.value = false
    range.value = null
    loading.value = true
    loadingMore.value = false
    try {
      const page = await loader(undefined, controller.signal)
      if (token !== requestToken) {
        return
      }
      items.value = unique(page.items)
      range.value = page.from && page.to ? { from: page.from, to: page.to } : null
      nextCursor.value = page.nextCursor
    } catch (requestError) {
      if (token === requestToken && !isAbortError(requestError)) {
        error.value = errorMessage(requestError)
      }
    } finally {
      if (token === requestToken) {
        loading.value = false
      }
    }
  }

  async function loadMore(
    loader: (cursor: string, signal: AbortSignal) => Promise<JankCursorPage<T>>,
  ): Promise<void> {
    const cursor = nextCursor.value
    if (!cursor || loading.value || loadingMore.value) {
      return
    }
    const token = requestToken
    controller = new AbortController()
    loadingMore.value = true
    appendError.value = null
    try {
      const page = await loader(cursor, controller.signal)
      if (token !== requestToken) {
        return
      }
      items.value = unique([...items.value, ...page.items])
      nextCursor.value = page.nextCursor
    } catch (requestError) {
      if (token === requestToken && !isAbortError(requestError)) {
        appendError.value = errorMessage(requestError)
        cursorInvalid.value = requestError instanceof JankApiError && requestError.code === 'INVALID_CURSOR'
        if (cursorInvalid.value) nextCursor.value = null
      }
    } finally {
      if (token === requestToken) {
        loadingMore.value = false
      }
    }
  }

  function unique(values: T[]): T[] {
    const keys = new Set<string>()
    return values.filter((item) => {
      const key = keyOf(item)
      if (keys.has(key)) {
        return false
      }
      keys.add(key)
      return true
    })
  }

  function cancel(): void {
    requestToken++
    controller?.abort()
    controller = null
    loading.value = false
    loadingMore.value = false
  }

  function reset(): void {
    cancel()
    items.value = []
    nextCursor.value = null
    error.value = null
    appendError.value = null
    cursorInvalid.value = false
    range.value = null
  }

  return {
    items,
    nextCursor,
    loading,
    loadingMore,
    error,
    appendError,
    cursorInvalid,
    range,
    load,
    loadMore,
    cancel,
    reset,
  }
}
