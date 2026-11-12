<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request, write, session } from '../api/client'
import { summaries, summaryKey, startSummary, stopSummary } from '../api/postSummary'
import PostMedia from '../components/PostMedia.vue'
import CommentSection from '../components/CommentSection.vue'

const route = useRoute()
const router = useRouter()
const post = ref(null)
const error = ref('')
const busy = ref(false)

const following = ref(null)
const followBusy = ref(false)
const followError = ref('')
const followNotice = ref('')
const own = computed(() => session.user?.id === post.value?.author?.user_id)
const summaryOpen = ref(false)
const eligibleSummary = computed(() => [...(post.value?.content || '')].length >= 200 && post.value?.status === 'PUBLISHED')
const currentSummary = computed(() => post.value && session.user ? summaries.get(summaryKey(post.value)) : null)
const summaryBusy = computed(() => ['waiting', 'generating'].includes(currentSummary.value?.status))
function summarize() {
  if (!session.user) { requireLogin(); return }
  summaryOpen.value = true
  startSummary(post.value, !!currentSummary.value)
}
let alive = true
onBeforeUnmount(() => { alive = false })
function requireLogin() { router.replace({ path: '/login', query: { from: route.fullPath } }) }
async function loadFollowing() {
  if (!session.user || own.value) return
  followBusy.value = true; followError.value = ''
  try {
    const profile = await request(`/api/users/${encodeURIComponent(post.value.author.user_id)}/profile`)
    if (alive) following.value = profile.is_following
  } catch (e) { if (alive) followError.value = e.message }
  finally { if (alive) followBusy.value = false }
}
async function toggleFollow() {
  if (!session.user) { requireLogin(); return }
  if (followBusy.value || following.value == null) return
  followBusy.value = true; followError.value = ''; followNotice.value = ''
  const active = !following.value
  try {
    await write('/api/interactions/follows', 'PUT', { targetId: post.value.author.user_id, active })
    if (alive) { following.value = active; followNotice.value = active ? '已关注作者' : '已取消关注' }
  } catch (e) { if (alive) followError.value = e.message }
  finally { if (alive) followBusy.value = false }
}
function timeAgo(ts) {
  if (!ts) return ''
  const diff = Math.max(0, Date.now() / 1000 - ts)
  if (diff < 60) return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`
  return new Date(ts * 1000).toLocaleDateString()
}
function hashtagSegments(text) {
  const re = /#[\w\u4e00-\u9fff]{1,20}/g
  const segments = []
  let last = 0, m
  while ((m = re.exec(text || ''))) {
    if (m.index > last) segments.push({ type: 'text', value: text.slice(last, m.index) })
    segments.push({ type: 'tag', value: m[0] })
    last = m.index + m[0].length
  }
  if (last < (text || '').length) segments.push({ type: 'text', value: text.slice(last) })
  return segments
}
const segments = computed(() => hashtagSegments(post.value?.content || ''))
const publishedTime = computed(() => post.value?.publish_time || post.value?.create_time)

async function load() {
  error.value = ''
  try {
    const result = await request(`/api/posts/${route.params.id}`)
    if (!alive) return
    post.value = result
    following.value = null
    loadFollowing()
  }
  catch (e) { error.value = e.message }
}
async function toggle(kind, active) {
  if (!session.user) { requireLogin(); return }
  busy.value = true
  try {
    await write(`/api/interactions/${kind}`, 'PUT', { targetId: post.value.post_id, active })
    if (kind === 'likes') post.value.is_liked = active
    if (kind === 'favorites') post.value.is_favorited = active
    post.value.like_count = Math.max(0, (post.value.like_count || 0) + (kind === 'likes' ? (active ? 1 : -1) : 0))
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
async function share() {
  const url = location.href
  try {
    if (navigator.share) await navigator.share({ title: post.value?.title, url })
    else { await navigator.clipboard.writeText(url); alert('链接已复制') }
  } catch { /* 用户取消 */ }
}
onMounted(load)
</script>

<template>
  <div>
    <p v-if="error" role="alert" class="error">{{ error }} <button class="quiet" @click="load">重试</button></p>
    <div v-else-if="!post" class="spinner">加载中…</div>
    <div v-else class="detail">
      <div class="detail-media"><PostMedia :clips="post.clips" /></div>
      <div class="detail-info">
        <h1>{{ post.title || '（无标题）' }}</h1>
        <div class="detail-author">
          <span class="avatar">{{ (post.author?.nickname || post.author?.username || 'U').charAt(0).toUpperCase() }}</span>
          <div class="meta">
            <RouterLink :to="`/author/${post.author?.user_id}`" class="nick">{{ post.author?.nickname }}</RouterLink>
            <small>{{ timeAgo(publishedTime) }}</small>
          </div>
          <button v-if="!own" class="secondary follow-button" :disabled="followBusy || (!!session.user && following == null)" @click="toggleFollow">{{ followBusy ? '请稍候…' : following === true ? '已关注' : '关注' }}</button>
        </div>
        <p v-if="followError" class="error" role="alert">{{ followError }} <button v-if="following == null" class="quiet" @click="loadFollowing">重试关注状态</button></p>
        <p v-if="followNotice" class="success" role="status">{{ followNotice }}</p>
        <div class="detail-actions">
          <button :class="{ on: post.is_liked }" :disabled="busy" @click="toggle('likes', !post.is_liked)">
            ♥ {{ post.is_liked ? '已赞' : '点赞' }} {{ post.like_count || 0 }}
          </button>
          <button class="secondary" :class="{ on: post.is_favorited }" :disabled="busy" @click="toggle('favorites', !post.is_favorited)">
            {{ post.is_favorited ? '已收藏' : '收藏' }}
          </button>
          <button class="secondary" @click="share">分享</button>
          <RouterLink :to="`/assistant/detail/${post.post_id}`">AI 助手</RouterLink>
        </div>
        <p class="detail-content">
          <template v-for="(seg, i) in segments" :key="i">
            <span v-if="seg.type === 'tag'" class="tag">{{ seg.value }}</span><template v-else>{{ seg.value }}</template>
          </template>
        </p>
        <section v-if="eligibleSummary" class="post-summary">
          <div class="feed-toolbar"><h2>长文提炼</h2><button class="secondary" @click="summaryOpen = !summaryOpen">{{ summaryOpen ? '收起' : '展开' }}</button></div>
          <div v-if="summaryOpen">
            <div class="work-actions"><button v-if="!currentSummary || !summaryBusy" class="secondary" @click="summarize">{{ currentSummary ? '重新提炼' : '开始提炼' }}</button><button v-else class="secondary" @click="stopSummary">停止</button></div>
            <template v-if="currentSummary">
              <p role="status">{{ { waiting: '等待中', generating: '正在提炼', done: '提炼完成', interrupted: '提炼未完成', failed: '提炼失败', cancelled: '已停止' }[currentSummary.status] }}</p>
              <p v-if="currentSummary.error" class="error" role="alert">{{ currentSummary.error }}</p>
              <p v-if="currentSummary.summary">{{ currentSummary.summary }}<span v-if="currentSummary.status !== 'done'" class="muted">（未完成）</span></p>
              <ol v-if="currentSummary.points.length"><li v-for="(point, index) in currentSummary.points" :key="index">{{ point }}</li></ol>
            </template>
          </div>
        </section>
        <CommentSection :post-id="post.post_id" />
      </div>
    </div>
  </div>
</template>
