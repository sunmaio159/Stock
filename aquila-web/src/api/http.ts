// 统一响应封装的 TS 类型（对齐 03 §三 {code, message, data, traceId}）
export interface Result<T> {
  code: number
  message: string
  data?: T
  traceId?: string
}

// 关键错误码（对齐后端 ErrorCode，仅前端需感知的子集）
export const BizCode = {
  SUCCESS: 0,
  RATE_LIMITED: 1004,
  UNAUTHORIZED: 2001,
  TOKEN_EXPIRED: 2002,
  DIMENSION_NOT_OPEN: 3007,
  SCORE_NOT_OPEN: 3008
} as const

const TOKEN_KEY = 'aquila_at'

export function setAccessToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}
export function getAccessToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
export function clearAccessToken() {
  localStorage.removeItem(TOKEN_KEY)
}

/** 统一 fetch 封装：注入 Bearer，解包 Result，非 0 code 抛业务错误。 */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const token = getAccessToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  headers.set('Content-Type', 'application/json')

  const resp = await fetch(path, { ...init, headers })
  // HTTP 恒 200（限流 429 除外），业务状态看 code
  const body = (await resp.json()) as Result<T>

  if (body.code === BizCode.UNAUTHORIZED || body.code === BizCode.TOKEN_EXPIRED) {
    clearAccessToken()
    throw new Error(`认证失效: ${body.message} (trace=${body.traceId ?? '-'})`)
  }
  if (body.code !== BizCode.SUCCESS) {
    throw new Error(`业务错误[${body.code}]: ${body.message} (trace=${body.traceId ?? '-'})`)
  }
  return body.data as T
}

export const api = {
  ping: () => request<Record<string, unknown>>('/api/ping')
}
