import { reactive, watch } from 'vue'
import { session, clearSession } from './client'

const running = ['waiting', 'retrieving', 'preparing', 'generating', 'validating']
export const agentSearch = reactive({ question: '', status: 'idle', phase: '', answer: '', sources: [], error: '' })
let controller = null
let run = 0

export function stopSearch() {
  run++
  controller?.abort()
  controller = null
  if (running.includes(agentSearch.status)) agentSearch.status = 'cancelled'
}
export function clearSearch() {
  stopSearch()
  Object.assign(agentSearch, { question: '', status: 'idle', phase: '', answer: '', sources: [], error: '' })
}
watch(() => session.generation, clearSearch)

export async function startSearch(question) {
  const q = question.trim()
  if (!session.user || !q || [...q].length > 200) return
  if (q === agentSearch.question && (running.includes(agentSearch.status) || agentSearch.status === 'done')) return
  stopSearch()
  const current = ++run
  const generation = session.generation
  const abort = new AbortController()
  controller = abort
  let timedOut = false
  let idleTimedOut = false
  const deadline = setTimeout(() => { timedOut = true; abort.abort() }, 55000)
  let idleDeadline
  const resetIdle = () => {
    clearTimeout(idleDeadline)
    idleDeadline = setTimeout(() => { idleTimedOut = true; abort.abort() }, 15000)
  }
  Object.assign(agentSearch, { question: q, status: 'waiting', phase: '', answer: '', sources: [], error: '' })
  let completed = false
  let expected = 1
  let requestId = ''
  let sessionId = ''
  let traceId = ''
  const active = () => run === current && generation === session.generation
  const validSources = sources => Array.isArray(sources) && sources.length <= 5 && sources.every(source =>
    source && /^[1-9][0-9]{0,17}$/.test(source.post_id) && typeof source.title === 'string' &&
    typeof source.author_name === 'string' && typeof source.excerpt === 'string')
  function receive(event, data) {
    if (!active()) return
    if (!data || !Number.isInteger(data.seq) || data.seq !== expected || typeof data.request_id !== 'string' || !data.request_id ||
        (requestId && requestId !== data.request_id) || (expected === 1 && event !== 'meta')) throw new Error('invalid event')
    expected++
    requestId = data.request_id
    if (event === 'meta') {
      if (expected !== 2 || !data.session_id || !data.trace_id) throw new Error('invalid meta')
      sessionId = data.session_id
      traceId = data.trace_id
    } else if (event === 'phase') {
      if (!['retrieving', 'preparing', 'generating', 'validating'].includes(data.phase)) throw new Error('invalid phase')
      agentSearch.phase = data.phase
      agentSearch.status = data.phase
    } else if (event === 'sources') {
      if (!validSources(data.sources)) throw new Error('invalid sources')
      agentSearch.sources = data.sources
    } else if (event === 'delta') {
      if (typeof data.text !== 'string') throw new Error('invalid delta')
      agentSearch.answer += data.text
      if ([...agentSearch.answer].length > 1500) throw new Error('answer too large')
      agentSearch.status = 'generating'
    } else if (event === 'done') {
      if (data.validated !== true || typeof data.answer !== 'string' || [...data.answer].length > 1500 ||
          !validSources(data.sources) || data.session_id !== sessionId || data.trace_id !== traceId ||
          ![0, 1].includes(data.degraded)) throw new Error('invalid done')
      agentSearch.answer = data.answer
      agentSearch.sources = data.sources
      agentSearch.status = 'done'
      completed = true
    } else if (event === 'error') {
      if (data.incomplete !== true) throw new Error('invalid error')
      agentSearch.status = 'interrupted'
      agentSearch.error = '回答中断，请手动重试'
      completed = true
    } else {
      throw new Error('unknown event')
    }
  }
  try {
    resetIdle()
    const response = await fetch('/api/agent/search/stream', {
      method: 'POST', cache: 'no-store', signal: abort.signal,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.tokens.accessToken}` },
      body: JSON.stringify({ question: q }),
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
      if (done) {
        buffer += decoder.decode()
        break
      }
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
      agentSearch.status = agentSearch.answer ? 'interrupted' : 'failed'
      agentSearch.error = timedOut ? '智能回答超时' : idleTimedOut ? '回答连接超时' : '回答未完成，请手动重试'
    }
  } finally {
    clearTimeout(deadline)
    clearTimeout(idleDeadline)
    abort.abort()
    if (active()) controller = null
  }
}
