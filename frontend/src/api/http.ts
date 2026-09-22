const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? ''

export interface ApiErrorOptions {
  status: number
  code?: string
  message: string
  retryable?: boolean
  details?: unknown
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string | null
  readonly retryable: boolean
  readonly details: unknown

  constructor(options: ApiErrorOptions) {
    super(options.message)
    this.name = 'ApiError'
    this.status = options.status
    this.code = options.code ?? null
    this.retryable = options.retryable ?? (options.status >= 500 || options.status === 408)
    this.details = options.details ?? null
  }
}

function csrfToken(): string | null {
  const token = document.cookie
    .split('; ')
    .find((cookie) => cookie.startsWith('XSRF-TOKEN='))
    ?.slice('XSRF-TOKEN='.length)
  return token ? decodeURIComponent(token) : null
}

function isStateChanging(method: string): boolean {
  return !['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase())
}

async function readError(response: Response): Promise<ApiError> {
  let payload: { code?: string; message?: string; errors?: unknown[] } | null = null
  try {
    payload = await response.json() as { code?: string; message?: string }
  } catch {
    payload = null
  }
  const fallbackMessage = response.status === 404
    ? '资源不存在或当前账号无权访问'
    : response.status === 401
      ? '登录状态已失效，请重新登录'
      : `请求失败（HTTP ${response.status}）`
  return new ApiError({
    status: response.status,
    code: payload?.code,
    message: payload?.message || fallbackMessage,
    details: payload?.errors,
  })
}

export interface RequestOptions extends RequestInit {
  skipAuthExpiry?: boolean
}

export async function requestJson<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = String(options.method ?? 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (isStateChanging(method)) {
    const token = csrfToken()
    if (token) {
      headers.set('X-XSRF-TOKEN', token)
    }
  }

  let response: Response
  try {
    response = await fetch(`${apiBaseUrl}${path}`, {
      ...options,
      method,
      headers,
      credentials: 'include',
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError({
      status: 0,
      code: 'NETWORK_ERROR',
      message: '无法连接服务，请检查后端是否已启动',
      retryable: true,
    })
  }

  if (!response.ok) {
    const error = await readError(response)
    if (error.status === 401 && !options.skipAuthExpiry) {
      window.dispatchEvent(new CustomEvent('apm:auth-expired'))
    }
    throw error
  }
  if (response.status === 204) {
    return undefined as T
  }
  try {
    return await response.json() as T
  } catch {
    throw new ApiError({ status: response.status, code: 'INVALID_RESPONSE', message: '服务端返回的数据格式无法识别' })
  }
}

export function pathSegment(value: string): string {
  return encodeURIComponent(value)
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  if (isAbortError(error)) {
    return '请求已取消'
  }
  return '请求失败，请稍后重试'
}
