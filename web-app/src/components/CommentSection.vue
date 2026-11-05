<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { request, write, session } from '../api/client'
const props = defineProps({ postId: { type: String, required: true } })
const router = useRouter()
const comments = ref([])
const cursor = ref(null)
const hasMore = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const draft = ref('')
const replyTarget = ref(null)
const error = ref('')
const sending = ref(false)

function timeAgo(ts) {
  const diff = Math.max(0, Date.now() / 1000 - ts)
  if (diff < 60) return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`
  return new Date(ts * 1000).toLocaleDateString()
}
function requireLogin() { router.replace(`/login?from=/post/${props.postId}`) }
function mine(c) { return session.user && c.author?.user_id === session.user.id }
async function load() {
  if (!session.user) { comments.value = []; hasMore.value = false; loading.value = false; return }
  loading.value = true; error.value = ''
  try {
    const page = await request(`/api/posts/${props.postId}/comments?count=20`)
    comments.value = page.comment_list || []
    hasMore.value = page.has_more === 1
    cursor.value = page.next_cursor
  } catch (e) { error.value = e.message } finally { loading.value = false }
}
async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const page = await request(`/api/posts/${props.postId}/comments?count=20&cursor=${cursor.value}`)
    comments.value = comments.value.concat(page.comment_list || [])
    hasMore.value = page.has_more === 1
    cursor.value = page.next_cursor
  } catch (e) { error.value = e.message } finally { loadingMore.value = false }
}
async function submit() {
  if (!session.user) { requireLogin(); return }
  const content = draft.value.trim()
  if (!content) return
  sending.value = true; error.value = ''
  try {
    const body = { content }
    if (replyTarget.value) body.parentCommentId = replyTarget.value.comment_id
    await write(`/api/posts/${props.postId}/comments`, 'POST', body)
    draft.value = ''
    replyTarget.value = null
    await load()
  } catch (e) { error.value = e.message } finally { sending.value = false }
}
function replyTo(c) {
  if (!session.user) { requireLogin(); return }
  replyTarget.value = c
  draft.value = ''
}
async function remove(c) {
  if (!session.user) { requireLogin(); return }
  if (!confirm('确定删除这条评论吗？')) return
  try {
    await request(`/api/comments/${c.comment_id}`, { method: 'DELETE' })
    await load()
  } catch (e) { error.value = e.message }
}
onMounted(load)
</script>

<template>
  <section class="comments">
    <h2>评论 {{ comments.length ? '' : '' }}</h2>
    <div v-if="!session.user" class="guest-tip">
      登录后即可查看和发表评论。 <RouterLink :to="`/login?from=/post/${postId}`">去登录</RouterLink>
    </div>
    <template v-if="session.user">
      <div class="comment-box">
      <span class="avatar">{{ session.user ? (session.user.nickname || session.user.username || 'U').charAt(0).toUpperCase() : '游' }}</span>
      <form @submit.prevent="submit">
        <textarea v-model="draft" :maxlength="500" :placeholder="replyTarget ? `回复 @${replyTarget.author?.nickname || ''}` : '写下你的评论…'"></textarea>
        <div class="row">
          <button v-if="replyTarget" type="button" class="quiet" @click="replyTarget = null; draft = ''">取消回复</button>
          <button type="submit" :disabled="sending || !draft.trim()">{{ sending ? '发送中…' : (replyTarget ? '回复' : '评论') }}</button>
        </div>
      </form>
    </div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>

    <div v-if="loading" class="spinner">加载评论中…</div>
    <template v-else>
      <div v-if="!comments.length" class="empty">还没有评论，来抢沙发～</div>
      <div v-for="c in comments" :key="c.comment_id" class="comment">
        <span class="avatar">{{ (c.author?.nickname || c.author?.username || 'U').charAt(0).toUpperCase() }}</span>
        <div class="body">
          <div class="head">
            <span class="nick">{{ c.author?.nickname }}</span>
            <span class="time">{{ timeAgo(c.create_time) }}</span>
          </div>
          <p class="text">{{ c.content }}</p>
          <div class="ops">
            <button class="quiet" @click="replyTo(c)">回复</button>
            <button v-if="mine(c)" class="danger" @click="remove(c)">删除</button>
          </div>
          <div v-if="c.replies?.length" class="replies">
            <div v-for="r in c.replies" :key="r.comment_id" class="comment">
              <span class="avatar">{{ (r.author?.nickname || 'U').charAt(0).toUpperCase() }}</span>
              <div class="body">
                <div class="head">
                  <span class="nick">{{ r.author?.nickname }}</span>
                  <span class="time">{{ timeAgo(r.create_time) }}</span>
                </div>
                <p class="text"><span v-if="r.parent_nickname" class="reply-to">回复 @{{ r.parent_nickname }}：</span>{{ r.content }}</p>
                <div class="ops">
                  <button class="quiet" @click="replyTo(r)">回复</button>
                  <button v-if="mine(r)" class="danger" @click="remove(r)">删除</button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <button v-if="hasMore" class="secondary load-more-comments" :disabled="loadingMore" @click="loadMore">
        {{ loadingMore ? '加载中…' : '加载更多评论' }}
      </button>
    </template>
    </template>
  </section>
</template>
