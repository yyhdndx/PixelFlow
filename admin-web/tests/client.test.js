import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { clearSession, session, request, login } from '../src/api/client'
const response = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers })
beforeEach(() => { clearSession(); vi.stubGlobal('fetch', vi.fn()) })
afterEach(() => vi.unstubAllGlobals())
it('attaches authentication and returns server data', async () => {
  session.tokens = { accessToken: 'access' }
  fetch.mockResolvedValue(response([]))
  expect(await request('/api/admin/users')).toEqual([])
  expect(fetch.mock.calls[0][1].headers.get('Authorization')).toBe('Bearer access')
})
it('shares one refresh across concurrent expired requests', async () => {
  session.tokens = { accessToken: 'old', refreshToken: 'refresh' }
  fetch.mockImplementation(async (path, options) => {
    if (path === '/api/auth/refresh') return response({ accessToken: 'new', refreshToken: 'next' })
    return options.headers.get('Authorization') === 'Bearer old' ? response({}, 401) : response([])
  })
  await Promise.all([request('/api/admin/posts'), request('/api/admin/users')])
  expect(fetch.mock.calls.filter(([path]) => path === '/api/auth/refresh')).toHaveLength(1)
  expect(session.tokens.accessToken).toBe('new')
})
it('clears expired sessions after refresh failure', async () => {
  session.tokens = { accessToken: 'old', refreshToken: 'refresh' }
  fetch.mockResolvedValue(response({ message: '会话失效' }, 401))
  await expect(request('/api/admin/users')).rejects.toThrow('会话失效')
  expect(session.tokens).toEqual({})
  expect(sessionStorage.length).toBe(0)
})
it('rejects ordinary accounts and revokes their web session', async () => {
  fetch.mockResolvedValueOnce(response({ accessToken: 'user' }))
    .mockResolvedValueOnce(response({ role: 'CREATOR' })).mockResolvedValueOnce(response({ success: true }))
  await expect(login('alice', 'password')).rejects.toThrow('管理员权限')
  expect(session.user).toBeNull()
  expect(session.tokens).toEqual({})
})
it('shows rate limit request id and retry time without retrying writes', async () => {
  fetch.mockResolvedValue(response({ message: '请求过于频繁', requestId: 'req-1' }, 429, { 'Retry-After': '8' }))
  await expect(request('/api/admin/posts/1/moderation', { method: 'POST', body: '{}' })).rejects.toThrow('请求过于频繁（请求 req-1），请在 8 秒后重试')
  expect(fetch).toHaveBeenCalledTimes(1)
})
it('discards a late response after logout', async () => {
  let resolve
  fetch.mockReturnValue(new Promise(done => { resolve = done }))
  const pending = request('/api/admin/users')
  clearSession(); resolve(response([{ id: 'other' }]))
  await expect(pending).rejects.toThrow('会话已变更')
})
it('never sends credentials to an external API', async () => {
  await expect(request('https://example.com/api/admin/users')).rejects.toThrow('仅允许')
  expect(fetch).not.toHaveBeenCalled()
})
