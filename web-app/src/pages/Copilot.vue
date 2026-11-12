<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { write } from '../api/client'

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

async function send() {
  const text = draftText.value.trim()
  if (!text || busy.value) return
  busy.value = true
  error.value = ''
  const s = ensureSession()
  s.entries.push({ role: 'user', text })
  s.updatedAt = Date.now()
  draftText.value = ''
  persist()
  await scrollBottom()
  try {
    const history = s.entries.slice(-10, -1).map((e) => ({ role: e.role, content: e.text }))
    const body = { screen: screen.value, message: text, history }
    if (screen.value === 'detail') body.postId = targetId.value
    if (screen.value === 'create') body.draftId = targetId.value
    if (screen.value === 'messages') body.conversationIds = [targetId.value]
    const reply = await write('/api/agent/copilot', 'POST', body)
    s.entries.push({ role: 'assistant', text: reply.reply || '', actions: reply.actions || [] })
    s.updatedAt = Date.now()
    persist()
  } catch (e) {
    error.value = e.message || '发送失败'
  } finally {
    busy.value = false
    await scrollBottom()
  }
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
      router.push(`/me/works/${targetId.value}/edit`)
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
  <section class="feature-shell content-page copilot-shell">
    <template v-if="!route.params.screen">
      <div class="feed-toolbar">
        <div><h1>AI 助手</h1><p class="muted">站内检索、整理与起草；也可以就某篇作品或草稿继续讨论。</p></div>
        <button type="button" @click="newGeneralChat">新建对话</button>
      </div>
      <div v-if="!sessions.length" class="copilot-history-empty">
        <h2>还没有对话</h2>
        <p class="muted">点「新建对话」开始，或在作品详情页点「AI 助手」进入讨论。</p>
      </div>
      <ul v-else class="copilot-history">
        <li v-for="s in sessions" :key="s.key" class="copilot-history-item">
          <RouterLink :to="`/assistant/${s.screen}/${s.targetId}`">{{ s.label }}</RouterLink>
          <small>{{ s.entries.length }} 条消息 · {{ new Date(s.updatedAt).toLocaleString() }}</small>
          <button type="button" class="danger" @click="removeSession(s.key)">删除</button>
        </li>
      </ul>
    </template>

    <template v-else>
      <div class="feed-toolbar">
        <div><h1>{{ label }}</h1><p class="muted">回复仅供参考，动作需你确认后才会执行。</p></div>
        <RouterLink to="/assistant">返回列表</RouterLink>
      </div>
      <div ref="scroller" class="copilot-conversation">
        <p v-if="!entries.length" class="muted">直接提问即可，例如「搜索站内关于秋天的帖子」。</p>
        <div v-for="(e, i) in entries" :key="i" :class="['copilot-bubble', e.role]">
          <p class="copilot-text">{{ e.text }}</p>
          <div v-if="e.actions && e.actions.length" class="copilot-actions">
            <div v-for="(a, ai) in e.actions" :key="ai" class="copilot-action">
              <p class="copilot-action-title">{{ a.type === 'CREATE_DRAFT' ? '新建草稿' : a.type === 'POST_COMMENT' ? '发送评论' : a.type === 'UPDATE_DRAFT' ? '修改草稿' : '发送私信' }}</p>
              <p v-if="a.title" class="copilot-action-text">{{ a.title }}</p>
              <p v-if="a.content" class="copilot-action-text">{{ a.content.slice(0, 200) }}</p>
              <p v-if="a.text" class="copilot-action-text">{{ a.text }}</p>
              <p v-if="a.items" class="copilot-action-text">{{ a.items.map((it) => it.text).join(' / ') }}</p>
              <p v-if="a.rationale" class="muted">依据：{{ a.rationale }}</p>
              <p v-if="e.appliedIndex === ai" class="success">
                已应用
                <RouterLink v-if="e.applied && e.applied.type === 'create'" :to="`/me/works/${e.applied.postId}/edit`">去编辑</RouterLink>
              </p>
              <button v-else type="button" @click="applyAction(e, a, ai)">应用</button>
            </div>
          </div>
        </div>
      </div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="copilot-composer">
        <label for="copilot-input">输入内容</label>
        <div class="copilot-composer-row">
          <input id="copilot-input" v-model="draftText" :disabled="busy" placeholder="例如：搜索站内关于秋天的帖子" @keyup.enter="send">
          <button type="button" :disabled="busy || !draftText.trim()" @click="send">{{ busy ? '思考中…' : '发送' }}</button>
        </div>
      </div>
      <RouterLink :to="back" class="copilot-context-link">返回</RouterLink>
    </template>
  </section>
</template>
