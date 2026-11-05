<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request, write, session } from '../api/client'
import PostMedia from '../components/PostMedia.vue'
import CommentSection from '../components/CommentSection.vue'

const route = useRoute()
const router = useRouter()
const post = ref(null)
const error = ref('')
const busy = ref(false)

function requireLogin() { router.replace(`/login?from=/post/${route.params.id}`) }
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
  try { post.value = await request(`/api/posts/${route.params.id}`) }
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
            <div class="nick">{{ post.author?.nickname }}</div>
            <small>{{ timeAgo(publishedTime) }}</small>
          </div>
        </div>
        <div class="detail-actions">
          <button :class="{ on: post.is_liked }" :disabled="busy" @click="toggle('likes', !post.is_liked)">
            ♥ {{ post.is_liked ? '已赞' : '点赞' }} {{ post.like_count || 0 }}
          </button>
          <button class="secondary" :class="{ on: post.is_favorited }" :disabled="busy" @click="toggle('favorites', !post.is_favorited)">
            {{ post.is_favorited ? '已收藏' : '收藏' }}
          </button>
          <button class="secondary" @click="share">分享</button>
        </div>
        <p class="detail-content">
          <template v-for="(seg, i) in segments" :key="i">
            <span v-if="seg.type === 'tag'" class="tag">{{ seg.value }}</span><template v-else>{{ seg.value }}</template>
          </template>
        </p>
        <CommentSection :post-id="post.post_id" />
      </div>
    </div>
  </div>
</template>
