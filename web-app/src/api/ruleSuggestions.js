import { reactive, watch } from 'vue'
import { session, clearSession } from './client'

export const ruleSuggestions = reactive(new Map())
let controller = null
let running = null
let sequence = 0

export function ruleKey(post) { return JSON.stringify([session.user?.id, post.post_id, post.content_version, post.title, post.content]) }
export function stopRuleSuggestion() {
  sequence++
  controller?.abort()
  controller = null
  if (running?.status === 'waiting') running.status = 'cancelled'
  running = null
}
watch(() => session.generation, () => { stopRuleSuggestion(); ruleSuggestions.clear() })
export function discardRuleSuggestion(post) {
  const key = ruleKey(post)
  if (running === ruleSuggestions.get(key)) stopRuleSuggestion()
  ruleSuggestions.delete(key)
}

export async function suggestRules(post, prompt) {
  const text = prompt.trim()
  if (!session.user || !post || !text || [...text].length > 500) return
  const key = ruleKey(post)
  const old = ruleSuggestions.get(key)
  if (old?.status === 'waiting' && old.prompt === text) return
  stopRuleSuggestion()
  const current = ++sequence
  const generation = session.generation
  const abort = new AbortController()
  controller = abort
  const task = reactive({ status: 'waiting', prompt: text, items: [], error: '' })
  ruleSuggestions.set(key, task)
  running = task
  const active = () => current === sequence && generation === session.generation
  let timedOut = false
  const timer = setTimeout(() => { timedOut = true; abort.abort() }, 55000)
  try {
    const response = await fetch(`/api/posts/${encodeURIComponent(post.post_id)}/auto-replies/suggest`, {
      method: 'POST', cache: 'no-store', signal: abort.signal,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.tokens.accessToken}` },
      body: JSON.stringify({ prompt: text }),
    })
    if (!active()) return
    if (!response.ok) {
      if (response.status === 401) { clearSession(); return }
      throw new Error('request failed')
    }
    const result = await response.json()
    if (!active()) return
    if (!Array.isArray(result.suggestions) || result.suggestions.length < 1 || result.suggestions.length > 5 ||
        !result.suggestions.every(item => item && typeof item.question === 'string' && item.question.trim() &&
          [...item.question].length <= 200 && typeof item.answer === 'string' && item.answer.trim() && [...item.answer].length <= 300)) throw new Error('invalid rules')
    task.items = result.suggestions.map(item => ({ question: item.question, answer: item.answer, selected: false }))
    task.status = 'done'
  } catch {
    if (active()) { task.status = 'failed'; task.error = timedOut ? '规则生成超时，请手动填写' : '规则生成失败，请手动填写或重试' }
  } finally {
    clearTimeout(timer)
    abort.abort()
    if (active()) { controller = null; running = null }
  }
}
