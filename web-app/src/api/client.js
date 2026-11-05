import { reactive } from 'vue'

const storageKey = 'pixelflow-web-session'
function restored() {
  try { return JSON.parse(sessionStorage.getItem(storageKey)) || {} } catch { return {} }
}
export const session = reactive({ tokens: restored(), user: null, generation: 0 })
let refreshing
export function clearSession() {
  session.tokens = {}
  session.user = null
  session.generation++
  sessionStorage.removeItem(storageKey)
}
function save(tokens) {
  session.tokens = tokens
  sessionStorage.setItem(storageKey, JSON.stringify(tokens))
}
export class ApiError extends Error {
  constructor(status, body, retryAfter) {
    super(`${body.message || '请求失败'}${body.requestId ? `（请求 ${body.requestId}）` : ''}${retryAfter ? `，请在 ${retryAfter} 秒后重试` : ''}`)
    this.status = status
  }
}
async function checked(response) {
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new ApiError(response.status, body, response.headers.get('Retry-After'))
  }
  return response.json()
}
export async function request(path, options = {}, retry = true) {
  if (!path.startsWith('/api/') && !path.startsWith('/feed/')) throw new Error('仅允许访问 Java API')
  const generation = session.generation
  const headers = new Headers(options.headers)
  if (session.tokens.accessToken) headers.set('Authorization', `Bearer ${session.tokens.accessToken}`)
  if (options.body) headers.set('Content-Type', 'application/json')
  const response = await fetch(path, { ...options, headers, cache: 'no-store' })
  if (generation !== session.generation) throw new Error('会话已变更，请重新操作')
  if (response.status === 401 && retry && session.tokens.refreshToken) {
    if (!refreshing) {
      refreshing = fetch('/api/auth/refresh', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: session.tokens.refreshToken }),
      }).then(r => checked(r)).then(tokens => {
        if (generation !== session.generation) throw new Error('会话已变更')
        save(tokens)
      }).catch(error => { if (generation === session.generation) clearSession(); throw error })
        .finally(() => { refreshing = null })
    }
    await refreshing
    return request(path, options, false)
  }
  if (response.status === 401) clearSession()
  const result = await checked(response)
  if (generation !== session.generation && response.status !== 401) throw new Error('会话已变更，请重新操作')
  return result
}
export async function identify() {
  session.user = await request('/api/users/me')
}
export async function login(username, password) {
  clearSession()
  const response = await fetch('/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }),
  })
  save(await checked(response))
  try { await identify() } catch (error) { clearSession(); throw error }
}
export async function register(username, password, nickname) {
  clearSession()
  const response = await fetch('/api/auth/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, nickname }),
  })
  await checked(response)
  return login(username, password)
}
export async function logout() {
  try { if (session.tokens.accessToken) await request('/api/auth/logout', { method: 'POST' }, false) }
  finally { clearSession() }
}
export const write = (path, method, body) => request(path, { method, body: JSON.stringify(body) })
