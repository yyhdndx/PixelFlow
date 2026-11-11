import { reactive, watch } from 'vue'
import { session, clearSession } from './client'

export const assists = reactive(new Map())
let controller = null
let running = null
let sequence = 0

export function draftKey(id) { return JSON.stringify([session.user?.id, id]) }
export function cancelAssist(id) {
  if (running?.key !== draftKey(id)) return
  sequence++
  controller?.abort()
  controller = null
  if (running.task.status === 'waiting') running.task.status = 'cancelled'
  running = null
}
export function discardAssist(id) {
  cancelAssist(id)
  assists.delete(draftKey(id))
}
watch(() => session.generation, () => {
  sequence++
  controller?.abort()
  controller = null
  running = null
  assists.clear()
})

export async function generateAssist(id, draft, goal, prompt) {
  if (!session.user || !id || !draft || !['all', 'improve_title', 'suggest_tags', 'polish_content', 'write_content'].includes(goal)) return
  const key = draftKey(id)
  const snapshot = { title: draft.title, content: draft.content, version: draft.version }
  const input = JSON.stringify({ draft: { title: snapshot.title, content: snapshot.content }, goal, prompt: prompt.trim() })
  const previous = assists.get(key)
  if (previous?.status === 'waiting' && previous.input === input && previous.snapshot.version === snapshot.version) return
  if (running) cancelAssist(JSON.parse(running.key)[1])
  const token = ++sequence
  const generation = session.generation
  const abort = new AbortController()
  controller = abort
  const task = reactive({ status: 'waiting', input, snapshot, goal, prompt: prompt.trim(), result: null, error: '' })
  assists.set(key, task)
  running = { key, task }
  const active = () => token === sequence && generation === session.generation
  let timedOut = false
  const timer = setTimeout(() => { timedOut = true; abort.abort() }, 55000)
  try {
    const response = await fetch('/api/agent/create-assist', {
      method: 'POST', cache: 'no-store', signal: abort.signal,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.tokens.accessToken}` },
      body: input,
    })
    if (!active()) return
    if (!response.ok) {
      if (response.status === 401) { clearSession(); return }
      throw new Error('request failed')
    }
    const result = await response.json()
    if (!active()) return
    if (!result.suggestion_id || !Array.isArray(result.title_candidates) || !Array.isArray(result.content_candidates) ||
        result.title_candidates.length > 1 || result.content_candidates.length > 1 ||
        (goal === 'suggest_tags' ? !Array.isArray(result.tag_suggestions) : !result.title_candidates.length && !result.content_candidates.length) ||
        !result.title_candidates.every(value => typeof value === 'string' && value.length <= 120) ||
        !result.content_candidates.every(value => typeof value === 'string' && value.length <= 5000)) throw new Error('invalid result')
    task.result = result
    task.status = 'done'
  } catch {
    if (active()) { task.status = 'failed'; task.error = timedOut ? '文案生成超时，请手动重试' : '文案生成失败，请手动重试' }
  } finally {
    clearTimeout(timer)
    abort.abort()
    if (active()) { controller = null; running = null }
  }
}
