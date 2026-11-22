import { reactive } from 'vue'
import type { ApiErrorResponse, Member, User } from './types'

const KEY = 'agile-demo-key', BASE = 'agile-demo-base'
export const session = reactive<{ key: string; base: string; user: User | null }>({key: sessionStorage.getItem(KEY) || '', base: sessionStorage.getItem(BASE) || import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080', user: null})
export function logout() { for (const name of Object.keys(sessionStorage)) if (name === KEY || name === BASE || name.startsWith('agile-draft-submit:')) sessionStorage.removeItem(name); session.key = ''; session.user = null }
export function saveSession(base: string, key: string, user: User) { session.base = base.replace(/\/$/, ''); session.key = key; session.user = user; sessionStorage.setItem(BASE, session.base); sessionStorage.setItem(KEY, key) }
export class ApiError extends Error { constructor(message: string, public code: string, public requestId: string) { super(message) } }
export function newId(): string { const bytes=crypto.getRandomValues(new Uint8Array(16)); bytes[6]=(bytes[6]! & 15)|64; bytes[8]=(bytes[8]! & 63)|128; const s=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join(''); return `${s.slice(0,8)}-${s.slice(8,12)}-${s.slice(12,16)}-${s.slice(16,20)}-${s.slice(20)}` }
export async function api<T>(path: string, options: RequestInit = {}, auth = true): Promise<T> {
  const requestId = newId(); const headers = new Headers(options.headers)
  headers.set('X-Request-Id', requestId)
  if (auth && session.key) headers.set('Authorization', `Bearer ${session.key}`)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const abort = new AbortController(); const timeout = setTimeout(() => abort.abort(), 120000)
  try {
    const response = await fetch(session.base.replace(/\/$/, '') + path, {...options, headers, signal: abort.signal, credentials: 'omit'})
    if (!response.ok) {
      let error: Partial<ApiErrorResponse> = {}; try { error = await response.json() as ApiErrorResponse } catch { /* response is not JSON */ }
      if (response.status === 401 && auth) { logout(); window.location.hash = '#/connect' }
      const message = error.message || (response.status === 403 ? '没有权限执行此操作' : '后端请求失败，请稍后重试')
      throw new ApiError(message, error.code || `HTTP_${response.status}`, error.requestId || response.headers.get('X-Request-Id') || requestId)
    }
    if (response.status === 204) return undefined as T
    return await response.json() as T
  } catch (e) {
    if (e instanceof ApiError) throw e
    throw new ApiError(abort.signal.aborted ? '请求超时，请检查后端状态后重试' : '无法连接后端，请检查地址、网络和跨域配置', 'NETWORK_ERROR', requestId)
  } finally { clearTimeout(timeout) }
}
export function describe(e: unknown): string { return e instanceof ApiError ? `${e.message}（${e.code}，请求 ID：${e.requestId}）` : '发生未知错误，请重试' }
export async function projectRole(projectId: string): Promise<string> { if (!session.user) session.user = await api<User>('/api/users/me'); const members=await api<Member[]>(`/api/projects/${projectId}/members`); return members.find(m=>m.userId===session.user?.id)?.role || 'UNKNOWN' }
