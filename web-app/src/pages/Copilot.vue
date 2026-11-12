<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request, session, write } from '../api/client'
import UserAvatar from '../components/UserAvatar.vue'

const route = useRoute()
const router = useRouter()
const STORE = 'pixelflow-web-copilot'

const screen = computed(() => route.params.screen || 'general')
const targetId = computed(() => String(route.params.id || ''))
const sessionKey = computed(() => `${screen.value}:${targetId.value}`)
const label = computed(() => {
  if (screen.value === 'detail') return `作品讨论 · #${targetId.value}`
  if (screen.value === 'create') return `创作建议 · #${targetId.value}`
  if (screen.value === 'messages') return `私信助手 · #${targetId.value}`
  return '通用对话'
})
const back = computed(() => {
  if (screen.value === 'detail' && /^[1-9][0-9]*$/.test(targetId.value)) return `/post/${targetId.value}`
  if (screen.value === 'create' && /^[1-9][0-9]*$/.test(targetId.value)) return `/me/works/${targetId.value}/edit`
  if (screen.value === 'messages' && /^[1-9][0-9]*$/.test(targetId.value)) return `/me/messages/${targetId.value}`
  return '/me'
})
const starters = computed(() => {
  if (screen.value === 'detail') return ['这篇作品讲了什么', '帮我写一条评论', '提炼一下重点']
  if (screen.value === 'create') return ['帮我把正文扩写一些', '优化一下标题', '给我几个话题建议']
  if (screen.value === 'messages') return ['总结这个会话', '帮我拟一条回复']
  return ['搜索站内关于秋天的帖子', '帮我整理一篇咖啡笔记', '推荐几个值得看的作品']
})

function loadAll() {
  try { return JSON.parse(localStorage.getItem(STORE) || '[]') } catch { return [] }
}
const sessions = ref(loadAll())
function persist() {
  localStorage.setItem(STORE, JSON.stringify(sessions.value))
}
const current = computed(() => sessions.value.find((s) => s.key === sessionKey.value) || null)
const entries = computed(() => (current.value ? current.value.entries : []))
const draftText = ref('')
const busy = ref(false)
const error = ref('')
const scroller = ref(null)

function ensureSession() {
  let found = sessions.value.find((s) => s.key === sessionKey.value)
  if (!found) {
    found = { key: sessionKey.value, screen: screen.value, targetId: targetId.value, label: label.value, updatedAt: Date.now(), entries: [] }
    sessions.value.unshift(found)
    persist()
  }
  return found
}

function newGeneralChat() {
  router.push('/assistant/general/' + Date.now().toString(36))
}

async function scrollBottom() {
  await nextTick()
  if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
}

function timeText(ts) {
  if (!ts) return ''
  return new Date(ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function actionTitle(a) {
  return { CREATE_DRAFT: '新建草稿', POST_COMMENT: '发送评论', UPDATE_DRAFT: '修改草稿', SEND_MESSAGES: '发送私信' }[a.type] || '操作'
}
function actionBadge(a) {
  return { CREATE_DRAFT: '草稿', POST_COMMENT: '评论', UPDATE_DRAFT: '编辑', SEND_MESSAGES: '私信' }[a.type] || '操作'
}

async function send(text) {
  const content = (typeof text === 'string' ? text : draftText.value).trim()
  if (!content || busy.value) return
  busy.value = true
  error.value = ''
  const s = ensureSession()
  s.entries.push({ role: 'user', text: content, time: Date.now() })
  s.updatedAt = Date.now()
  draftText.value = ''
  persist()
  await scrollBottom()
  try {
    const history = s.entries.slice(-10, -1).map((e) => ({ role: e.role, content: e.text }))
    const body = { screen: screen.value, message: content, history }
    if (screen.value === 'detail') body.postId = targetId.value
    if (screen.value === 'create') body.draftId = targetId.value
    if (screen.value === 'messages') body.conversationIds = [targetId.value]
    const reply = await write('/api/agent/copilot', 'POST', body)
    s.entries.push({ role: 'assistant', text: reply.reply || '', actions: reply.actions || [], time: Date.now() })
    s.updatedAt = Date.now()
    persist()
  } catch (e) {
    error.value = e.message || '发送失败'
  } finally {
    busy.value = false
    await scrollBottom()
  }
}

function useStarter(text) {
  draftText.value = text
  send(text)
}

async function applyAction(entry, action, index) {
  error.value = ''
  try {
    if (action.type === 'CREATE_DRAFT') {
      const post = await write('/api/posts', 'POST', { title: action.title || '', content: action.content || '', mediaIds: [] })
      entry.applied = { type: 'create', postId: post.post_id }
    } else if (action.type === 'POST_COMMENT') {
      await write(`/api/posts/${targetId.value}/comments`, 'POST', { content: action.text })
      entry.applied = { type: 'comment' }
    } else if (action.type === 'SEND_MESSAGES') {
      const items = (action.items || []).map((i) => ({ conversation_id: i.conversation_id, content: i.text }))
      if (items.length) await write('/api/conversations/messages/batch', 'POST', { items })
      entry.applied = { type: 'messages' }
    } else if (action.type === 'UPDATE_DRAFT') {
      const post = await request(`/api/posts/${targetId.value}`)
      const updated = await write(`/api/posts/${targetId.value}/copilot-application`, 'POST', {
        title: action.title || post.title || '',
        content: action.content || post.content || '',
        expected_version: post.content_version ?? 0,
      })
      entry.applied = { type: 'update', version: updated.content_version }
    }
    entry.appliedIndex = index
    persist()
  } catch (e) {
    error.value = e.message || '应用失败'
  }
}

function removeSession(key) {
  sessions.value = sessions.value.filter((s) => s.key !== key)
  persist()
  if (key === sessionKey.value) router.push('/assistant')
}

watch(() => route.fullPath, () => { error.value = '' })
</script>

<template>
  <section class="chat-page">
    <div v-if="!route.params.screen" class="chat-list">
      <header class="chat-list-head">
        <div>
          <h1>AI 助手</h1>
          <p>站内检索、整理与起草，也可以就某篇作品或草稿继续讨论。</p>
        </div>
        <button type="button" @click="newGeneralChat">＋ 新建对话</button>
      </header>
      <div v-if="!sessions.length" class="chat-list-empty">
        <span class="chat-avatar ai">AI</span>
        <p class="chat-empty-title">还没有对话</p>
        <p class="chat-empty-sub">点右上角「新建对话」，或在作品详情页点「AI 助手」。</p>
      </div>
      <ul v-else class="chat-list-items">
        <li v-for="s in sessions" :key="s.key">
          <RouterLink :to="`/assistant/${s.screen}/${s.targetId}`" class="chat-list-link">
            <span class="chat-avatar ai small">AI</span>
            <span class="chat-list-meta">
              <strong>{{ s.label }}</strong>
              <small>{{ s.entries.length }} 条消息 · {{ new Date(s.updatedAt).toLocaleString('zh-CN') }}</small>
            </span>
          </RouterLink>
          <button type="button" class="icon-btn" aria-label="删除对话" @click="removeSession(s.key)">×</button>
        </li>
      </ul>
    </div>

    <div v-else class="chat-shell">
      <header class="chat-head">
        <div class="chat-head-main">
          <span class="chat-avatar ai small">AI</span>
          <div class="chat-head-text">
            <strong>{{ label }}</strong>
            <small>{{ screen === 'messages' ? '基于会话内容回答' : screen === 'detail' ? '基于这篇作品回答' : screen === 'create' ? '基于当前草稿回答' : '可检索站内内容并起草' }}</small>
          </div>
        </div>
        <div class="chat-head-actions">
          <RouterLink :to="back" class="ghost-link">返回</RouterLink>
          <RouterLink to="/assistant" class="ghost-link">全部对话</RouterLink>
        </div>
      </header>

      <div ref="scroller" class="chat-body">
        <div v-if="!entries.length && !busy" class="chat-empty">
          <span class="chat-avatar ai">AI</span>
          <p class="chat-empty-title">今天想创作点什么？</p>
          <p class="chat-empty-sub">把问题交给我，我会结合站内内容回答；涉及写操作会先给你确认。</p>
          <div class="chat-chips">
            <button v-for="s in starters" :key="s" type="button" class="chip" @click="useStarter(s)">{{ s }}</button>
          </div>
        </div>

        <div v-for="(e, i) in entries" :key="i" :class="['msg', e.role]">
          <UserAvatar v-if="e.role === 'user'" :user="session.user" :size="30" />
          <span v-else class="chat-avatar ai small">AI</span>
          <div class="msg-main">
            <div class="msg-bubble">
              <p class="msg-text">{{ e.text }}</p>
            </div>
            <div v-if="e.actions && e.actions.length" class="msg-actions">
              <div v-for="(a, ai) in e.actions" :key="ai" class="action-card">
                <div class="action-head">
                  <span class="action-badge">{{ actionBadge(a) }}</span>
                  <strong>{{ actionTitle(a) }}</strong>
                </div>
                <p v-if="a.title" class="action-title">{{ a.title }}</p>
                <p v-if="a.content" class="action-text">{{ a.content.slice(0, 180) }}</p>
                <p v-if="a.text" class="action-text">{{ a.text }}</p>
                <p v-if="a.items" class="action-text">{{ a.items.map((it) => it.text).join(' / ') }}</p>
                <p v-if="a.rationale" class="action-note">依据：{{ a.rationale }}</p>
                <div class="action-foot">
                  <span v-if="e.appliedIndex === ai" class="action-done">
                    ✓ 已应用
                    <RouterLink v-if="e.applied && e.applied.type === 'create'" :to="`/me/works/${e.applied.postId}/edit`">去编辑</RouterLink>
                    <RouterLink v-else-if="e.applied && e.applied.type === 'update'" :to="`/me/works/${targetId}/edit`">去编辑</RouterLink>
                  </span>
                  <button v-else type="button" class="solid-sm" @click="applyAction(e, a, ai)">应用这项</button>
                </div>
              </div>
            </div>
            <small class="msg-time">{{ timeText(e.time) }}</small>
          </div>
        </div>

        <div v-if="busy" class="msg assistant">
          <span class="chat-avatar ai small">AI</span>
          <div class="msg-main">
            <div class="msg-bubble typing"><span /><span /><span /></div>
          </div>
        </div>
      </div>

      <p v-if="error" class="chat-error" role="alert">{{ error }}</p>

      <footer class="chat-composer">
        <div class="chat-input-wrap">
          <input id="copilot-input" v-model="draftText" :disabled="busy" maxlength="2000"
                 placeholder="给 AI 助手发消息…" @keyup.enter="send()">
          <button class="chat-send" type="button" :disabled="busy || !draftText.trim()" aria-label="发送" @click="send()">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5"/><path d="M5 12l7-7 7 7"/></svg>
          </button>
        </div>
        <p class="chat-tip">Enter 发送 · 回复仅供参考，写入操作需你确认</p>
      </footer>
    </div>
  </section>
</template>

<style scoped>
.chat-page { width: min(100%, 980px); margin: 0 auto; }
.chat-avatar {
  width: 34px; height: 34px; border-radius: 12px; flex-shrink: 0;
  display: inline-grid; place-items: center; font-size: 13px; font-weight: 800; color: #fff;
  background: linear-gradient(135deg, var(--brand-500), #7a5af8); letter-spacing: .5px;
}
.chat-avatar.small { width: 30px; height: 30px; border-radius: 10px; font-size: 12px; }

/* 会话列表 */
.chat-list { background: var(--surface); border: 1px solid var(--line); border-radius: 20px; padding: 26px 26px 18px; box-shadow: 0 16px 44px #25314a12; }
.chat-list-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.chat-list-head h1 { margin: 0; font-size: 24px; }
.chat-list-head p { margin: 6px 0 0; color: var(--ink-500); font-size: 13.5px; }
.chat-list-empty { padding: 48px 0 36px; text-align: center; }
.chat-list-items { list-style: none; margin: 18px 0 0; padding: 0; display: grid; gap: 10px; }
.chat-list-items li { display: flex; align-items: center; gap: 8px; }
.chat-list-link { flex: 1; display: flex; align-items: center; gap: 12px; padding: 12px 14px; border: 1px solid var(--line); border-radius: 14px; background: #fbfcff; color: var(--ink-900); transition: border-color .15s, background .15s; }
.chat-list-link:hover { border-color: var(--brand-100); background: var(--brand-50); }
.chat-list-meta { display: grid; gap: 2px; min-width: 0; }
.chat-list-meta small { color: var(--ink-400); }
.icon-btn { width: 32px; height: 32px; padding: 0; border-radius: 10px; background: transparent; color: var(--ink-400); box-shadow: none; font-size: 18px; }
.icon-btn:hover { background: var(--danger-50); color: var(--danger-600); filter: none; }

/* 对话主体 */
.chat-shell {
  display: flex; flex-direction: column; height: calc(100vh - 62px - 56px); min-height: 460px;
  background: var(--surface); border: 1px solid var(--line); border-radius: 20px;
  box-shadow: 0 18px 50px #25314a14; overflow: hidden;
}
.chat-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 18px; border-bottom: 1px solid var(--line); background: #fdfdff; }
.chat-head-main { display: flex; align-items: center; gap: 10px; min-width: 0; }
.chat-head-text { display: grid; gap: 1px; min-width: 0; }
.chat-head-text strong { font-size: 15px; }
.chat-head-text small { color: var(--ink-400); font-size: 12px; }
.chat-head-actions { display: flex; gap: 8px; flex-shrink: 0; }
.ghost-link { padding: 6px 12px; border-radius: 9px; font-size: 12.5px; font-weight: 600; color: var(--brand-600); background: var(--brand-50); }
.ghost-link:hover { background: var(--brand-100); }

.chat-body { flex: 1; overflow-y: auto; padding: 22px 22px 8px; display: flex; flex-direction: column; gap: 18px; }

.chat-empty { margin: auto; text-align: center; max-width: 460px; padding: 20px 0; }
.chat-empty .chat-avatar { width: 52px; height: 52px; border-radius: 16px; font-size: 18px; margin-bottom: 14px; }
.chat-empty-title { margin: 0 0 6px; font-size: 19px; font-weight: 750; }
.chat-empty-sub { margin: 0; color: var(--ink-500); font-size: 13.5px; line-height: 1.6; }
.chat-chips { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; margin-top: 18px; }
.chip { background: var(--brand-50); color: var(--brand-700); box-shadow: none; border: 1px solid var(--brand-100); padding: 7px 13px; font-size: 12.5px; font-weight: 600; border-radius: 999px; }
.chip:hover { background: var(--brand-100); filter: none; }

.msg { display: flex; gap: 10px; align-items: flex-start; }
.msg.user { flex-direction: row-reverse; }
.msg-main { display: grid; gap: 6px; max-width: min(78%, 660px); }
.msg.user .msg-main { justify-items: end; }
.msg-bubble { padding: 11px 14px; border-radius: 16px; border: 1px solid var(--line); background: #f7f9ff; }
.msg.assistant .msg-bubble { background: #f7f9ff; border-top-left-radius: 6px; }
.msg.user .msg-bubble { background: linear-gradient(135deg, var(--brand-500), var(--brand-600)); border-color: transparent; color: #fff; border-top-right-radius: 6px; }
.msg-text { margin: 0; white-space: pre-wrap; font-size: 14px; line-height: 1.72; }
.msg-time { color: var(--ink-400); font-size: 11px; }
.msg-bubble.typing { display: inline-flex; gap: 5px; align-items: center; padding: 14px 16px; }
.msg-bubble.typing span { width: 6px; height: 6px; border-radius: 50%; background: var(--ink-400); animation: blink 1.2s infinite ease-in-out; }
.msg-bubble.typing span:nth-child(2) { animation-delay: .18s; }
.msg-bubble.typing span:nth-child(3) { animation-delay: .36s; }
@keyframes blink { 0%, 80%, 100% { opacity: .25; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-2px); } }

.msg-actions { display: grid; gap: 8px; width: 100%; }
.action-card { border: 1px solid var(--line); border-radius: 14px; padding: 12px 14px; background: var(--surface); box-shadow: 0 6px 18px #25314a0d; }
.action-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.action-head strong { font-size: 13.5px; }
.action-badge { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 999px; color: var(--brand-700); background: var(--brand-50); }
.action-title { margin: 0 0 4px; font-weight: 700; font-size: 13.5px; }
.action-text { margin: 0 0 6px; font-size: 13px; color: var(--ink-700); line-height: 1.65; white-space: pre-wrap; }
.action-note { margin: 0 0 8px; font-size: 12px; color: var(--ink-400); line-height: 1.55; }
.action-foot { display: flex; justify-content: flex-end; }
.action-done { font-size: 12.5px; color: var(--ok-600); font-weight: 600; }
.action-done a { margin-left: 6px; }
.solid-sm { padding: 6px 14px; font-size: 12.5px; border-radius: 9px; }

.chat-error { margin: 0; padding: 10px 22px; color: var(--danger-700); background: var(--danger-50); border-top: 1px solid #f3c9d2; font-size: 13px; }

.chat-composer { border-top: 1px solid var(--line); padding: 14px 18px 12px; background: #fdfdff; }
.chat-input-wrap { display: flex; align-items: center; gap: 10px; border: 1px solid var(--line-strong); border-radius: 14px; padding: 6px 6px 6px 14px; background: var(--surface); transition: border-color .15s, box-shadow .15s; }
.chat-input-wrap:focus-within { border-color: var(--brand-500); box-shadow: 0 0 0 3px #4f5dd51f; }
.chat-input-wrap input { border: none; box-shadow: none; padding: 8px 0; flex: 1; background: transparent; }
.chat-input-wrap input:focus { border: none; box-shadow: none; }
.chat-send { width: 38px; height: 38px; padding: 0; border-radius: 11px; display: inline-grid; place-items: center; flex-shrink: 0; }
.chat-send:disabled { opacity: .45; }
.chat-tip { margin: 8px 2px 0; font-size: 11.5px; color: var(--ink-400); }

@media (max-width: 720px) {
  .chat-shell { height: calc(100vh - 62px - 32px); border-radius: 16px; }
  .msg-main { max-width: 88%; }
  .chat-body { padding: 16px 14px 6px; }
}
</style>
