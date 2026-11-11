import { reactive, watch } from 'vue'
import { session, clearSession } from './client'

export const summaries = reactive(new Map())
let controller = null
let runningTask = null
let run = 0

export function summaryKey(post) {
  return JSON.stringify([session.user?.id, post.post_id, post.content_version, post.title, post.content])
}
export function stopSummary() {
  run++
  controller?.abort()
  controller = null
  if (runningTask && ['waiting', 'generating'].includes(runningTask.status)) runningTask.status = 'cancelled'
  runningTask = null
}
watch(() => session.generation, () => { stopSummary(); summaries.clear() })

export async function startSummary(post, refresh = false) {
  if (!session.user || !post || [...(post.content || '')].length < 200) return
  const key = summaryKey(post)
  const existing = summaries.get(key)
  if (!refresh && existing && ['waiting', 'generating', 'done'].includes(existing.status)) return
  stopSummary()
  const current = ++run
  const generation = session.generation
  const abort = new AbortController()
  controller = abort
  const active = () => current === run && generation === session.generation
  const task = reactive({ status: 'waiting', summary: '', points: [], error: '' })
  summaries.set(key, task)
  runningTask = task
  while (summaries.size > 12) {
    const oldest = summaries.keys().next().value
    if (oldest === key) break
    summaries.delete(oldest)
  }
  let timedOut = false
  let idleTimedOut = false
  const deadline = setTimeout(() => { timedOut = true; abort.abort() }, 55000)
  let idleDeadline
  const resetIdle = () => {
    clearTimeout(idleDeadline)
    idleDeadline = setTimeout(() => { idleTimedOut = true; abort.abort() }, 15000)
  }
  let seq = 1
  let completed = false
  function receive(event, data) {
    if (!active()) return
    if (!data || data.seq !== seq++) throw new Error('invalid sequence')
    if (event === 'summary') {
      if (task.summary || typeof data.summary !== 'string' || !data.summary.trim() || [...data.summary].length > 60) throw new Error('invalid summary')
      task.summary = data.summary
      task.status = 'generating'
    } else if (event === 'point') {
      if (!task.summary || task.points.length >= 5 || typeof data.point !== 'string' || !data.point.trim() || [...data.point].length > 80) throw new Error('invalid point')
      task.points.push(data.point)
    } else if (event === 'done') {
      if (data.validated !== true || data.summary !== task.summary ||
          !Array.isArray(data.points) || data.points.length < 3 || data.points.length > 5 ||
          JSON.stringify(data.points) !== JSON.stringify(task.points) ||
          !Number.isInteger(data.content_length) || data.content_length < 200 || !data.trace_id) throw new Error('invalid done')
      task.status = 'done'
      completed = true
    } else if (event === 'error') {
      if (data.incomplete !== true) throw new Error('invalid error')
      task.status = 'interrupted'
      task.error = '提炼未完成，请手动重试'
      completed = true
    } else throw new Error('unknown event')
  }
  try {
    resetIdle()
    const response = await fetch(`/api/posts/${encodeURIComponent(post.post_id)}/summary/stream`, {
      headers: { Authorization: `Bearer ${session.tokens.accessToken}` }, cache: 'no-store', signal: abort.signal,
    })
    if (!active()) return
    if (!response.ok) {
      if (response.status === 401) { clearSession(); return }
      throw new Error('request failed')
    }
    if (!response.headers.get('Content-Type')?.toLowerCase().includes('text/event-stream') || !response.body) throw new Error('invalid stream')
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8', { fatal: true })
    let buffer = ''
    let bytes = 0
    while (active() && !completed) {
      const { value, done } = await reader.read()
      if (done) { buffer += decoder.decode(); break }
      resetIdle()
      bytes += value.byteLength
      if (bytes > 262144) throw new Error('stream too large')
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      if (new TextEncoder().encode(buffer).length > 16384 && !buffer.includes('\n\n')) throw new Error('frame too large')
      let boundary
      while ((boundary = buffer.indexOf('\n\n')) !== -1 && !completed) {
        const frame = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        if (new TextEncoder().encode(frame).length > 16384) throw new Error('frame too large')
        if (!frame || frame.split('\n').every(line => line.startsWith(':'))) continue
        const event = frame.match(/^event: (.+)$/m)?.[1]
        const data = frame.match(/^data: (.+)$/m)?.[1]
        if (!event || !data) throw new Error('invalid frame')
        receive(event, JSON.parse(data))
      }
    }
    if (active() && !completed) throw new Error('incomplete stream')
  } catch {
    if (active()) {
      task.status = task.summary ? 'interrupted' : 'failed'
      task.error = timedOut ? '提炼超时，请先读原文' : idleTimedOut ? '提炼连接超时' : '提炼未完成，请先读原文'
    }
  } finally {
    clearTimeout(deadline)
    clearTimeout(idleDeadline)
    abort.abort()
    if (active()) { controller = null; runningTask = null }
  }
}
