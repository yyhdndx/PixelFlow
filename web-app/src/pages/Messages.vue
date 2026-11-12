<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { request, write, session } from '../api/client'

const route = useRoute()
const conversationId = computed(() =>
  typeof route.params.id === 'string' && /^[1-9][0-9]*$/.test(route.params.id) ? route.params.id : '')

const conversations = ref([])
const loadingList = ref(false)
const listError = ref('')
const messages = ref([])
const loadingMessages = ref(false)
const messageError = ref('')
const draft = ref('')
const sending = ref(false)
const scroller = ref(null)
let timer = null

function timeAgo(ts) {
  if (!ts) return ''
  const diff = Math.max(0, Date.now() / 1000 - ts)
  if (diff < 60) return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`
  return new Date(ts * 1000).toLocaleDateString()
}

async function loadList() {
  loadingList.value = true
  listError.value = ''
  try {
    const page = await request('/api/conversations?count=50')
    conversations.value = page.conversation_list || []
  } catch (e) {
    listError.value = e.message
  } finally {
    loadingList.value = false
  }
}

async function loadMessages({ silent = false } = {}) {
  if (!conversationId.value) { messages.value = []; return }
  if (!silent) loadingMessages.value = true
  messageError.value = ''
  try {
    const page = await request(`/api/conversations/${conversationId.value}/messages?count=50`)
    messages.value = (page.message_list || []).slice().reverse()
    await scrollBottom()
  } catch (e) {
    if (!silent) messageError.value = e.message
  } finally {
    loadingMessages.value = false
  }
}

async function poll() {
  if (!conversationId.value) return
  const last = messages.value[messages.value.length - 1]
  try {
    const page = await request(`/api/conversations/${conversationId.value}/messages?count=50${last ? `&after=${last.message_id}` : ''}`)
    const incoming = page.message_list || []
    if (incoming.length) {
      messages.value = messages.value.concat(incoming)
      await scrollBottom()
    }
  } catch { /* 轮询失败静默 */ }
}

async function send() {
  const content = draft.value.trim()
  if (!content || sending.value) return
  sending.value = true
  messageError.value = ''
  try {
    await write(`/api/conversations/${conversationId.value}/messages`, 'POST', { content })
    draft.value = ''
    await loadMessages({ silent: true })
  } catch (e) {
    messageError.value = e.message
  } finally {
    sending.value = false
  }
}

async function scrollBottom() {
  await nextTick()
  if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
}

const partner = computed(() => {
  const item = conversations.value.find((c) => String(c.conversation_id) === conversationId.value)
  return item ? item.partner : null
})

watch(conversationId, async () => { await loadMessages() })

onMounted(async () => {
  await loadList()
  if (conversationId.value) await loadMessages()
  timer = setInterval(poll, 5000)
})

onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <section class="feature-shell content-page">
    <div class="feed-toolbar">
      <div>
        <h1>{{ conversationId ? (partner ? partner.nickname : '私信会话') : '私信' }}</h1>
        <p v-if="!conversationId" class="muted">按最后消息时间排序，点开即可继续对话。</p>
      </div>
      <div class="work-actions">
        <RouterLink v-if="conversationId" :to="`/assistant/messages/${conversationId}`">AI 助手</RouterLink>
        <RouterLink :to="conversationId ? '/me/messages' : '/me'">{{ conversationId ? '返回私信' : '返回个人中心' }}</RouterLink>
      </div>
    </div>

    <template v-if="!conversationId">
      <p v-if="listError" class="error" role="alert">{{ listError }}</p>
      <div v-if="loadingList" class="empty">加载中…</div>
      <div v-else-if="!conversations.length" class="empty">还没有私信。去作者主页发起对话试试。</div>
      <ul v-else class="copilot-history">
        <li v-for="c in conversations" :key="c.conversation_id" class="copilot-history-item">
          <RouterLink :to="`/me/messages/${c.conversation_id}`">
            {{ c.partner?.nickname || '私信会话' }}
            <span v-if="c.unread_count" class="nav-badge">{{ c.unread_count }}</span>
          </RouterLink>
          <small>{{ c.last_message || '还没有消息' }} · {{ timeAgo(c.last_message_time) }}</small>
        </li>
      </ul>
    </template>

    <template v-else>
      <div ref="scroller" class="copilot-conversation">
        <p v-if="loadingMessages" class="muted">加载中…</p>
        <p v-else-if="!messages.length" class="muted">还没有消息，发第一条吧。</p>
        <div v-for="m in messages" :key="m.message_id"
             :class="['copilot-bubble', m.sender_id === session.user?.id ? 'user' : 'assistant']">
          <p class="copilot-text">{{ m.content }}</p>
          <small>{{ timeAgo(m.create_time) }}</small>
        </div>
      </div>
      <p v-if="messageError" class="error" role="alert">{{ messageError }}</p>
      <div class="copilot-composer">
        <label for="message-input">发送消息</label>
        <div class="copilot-composer-row">
          <input id="message-input" v-model="draft" maxlength="1000" :disabled="sending"
                 placeholder="输入消息（≤1000 字）" @keyup.enter="send">
          <button type="button" :disabled="sending || !draft.trim()" @click="send">{{ sending ? '发送中…' : '发送' }}</button>
        </div>
      </div>
    </template>
  </section>
</template>
