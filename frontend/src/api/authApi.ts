import type { CurrentUser, LoginRequest } from '../types/auth'
import { requestJson } from './http'

export const authApi = {
  session() {
    return requestJson<CurrentUser>('/api/v1/session', { skipAuthExpiry: true })
  },
  login(request: LoginRequest) {
    return requestJson<CurrentUser>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(request),
      skipAuthExpiry: true,
    })
  },
  logout() {
    return requestJson<void>('/api/v1/auth/logout', { method: 'POST' })
  },
}
