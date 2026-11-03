<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { request, session } from '../api/client'
import PostCard from '../components/PostCard.vue'

const tab = ref('recommend')
const posts = ref([])
const cursor = ref(null)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')
const sentinel = ref(null)
let revision = 0
let observer = null

function feedPath() {
  return tab.value === 'recommend' ? '/feed/?count=20&accept_video_clip=true' : '/api/feed/following?count=20'
}
async function load(reset) {
  if (tab.value === 'following' && !session.user) { posts.value = []; hasMore.value = false; return }
  const current = ++revision
  loading.value = true; error.value = ''
  try {
    const page = await request(feedPath() + (reset || cursor.value == null ? '' : `&cursor=${cursor.value}`))
    if (current !== revision) return
    posts.value = reset ? (page.post_list || []) : posts.value.concat(page.post_list || [])
    hasMore.value = page.has_more === 1
    cursor.value = page.next_cursor
  } catch (e) {
    if (current !== revision) return
    error.value = e.message
  } finally { if (current === revision) loading.value = false }
}
function refresh() { cursor.value = null; load(true) }
function switchTab(next) {
  if (tab.value === next) return
  tab.value = next
  cursor.value = null
  posts.value = []
  hasMore.value = false
  error.value = ''
  load(true)
}
function setupObserver() {
  observer = new IntersectionObserver(entries => {
    if (entries[0].isIntersecting && hasMore.value && !loading.value) load(false)
  }, { rootMargin: '400px' })
  if (sentinel.value) observer.observe(sentinel.value)
}
watch(sentinel, el => { if (observer) observer.disconnect(); if (el) observer.observe(el) })
onMounted(() => { load(true); setupObserver() })
onBeforeUnmount(() => observer && observer.disconnect())
</script>

<template>
  <div>
    <div class="feed-tabs" role="tablist">
      <button :class="['tab', { on: tab === 'recommend' }]" @click="switchTab('recommend')">推荐</button>
      <button :class="['tab', { on: tab === 'following' }]" @click="switchTab('following')">关注</button>
    </div>
    <div class="feed-toolbar">
      <span class="hint">{{ tab === 'recommend' ? '社区最新公开作品' : '我关注的作者最新作品' }}</span>
      <button class="secondary" :disabled="loading" @click="refresh">刷新</button>
    </div>

    <p v-if="error" role="alert" class="error">{{ error }} <button class="quiet" @click="refresh">重试</button></p>

    <div v-if="tab === 'following' && !session.user" class="empty">
      登录后查看关注流。<RouterLink to="/login?from=/">去登录</RouterLink>
    </div>
    <template v-else>
      <div v-if="loading && !posts.length" class="spinner">加载中…</div>
      <div v-else-if="!posts.length && !error" class="empty">暂时没有作品，稍后再来看看吧。</div>
      <div v-else class="waterfall">
        <PostCard v-for="p in posts" :key="p.post_id" :post="p" />
      </div>
      <div v-if="loading && posts.length" class="spinner">加载更多…</div>
      <div ref="sentinel" class="sentinel"></div>
    </template>
  </div>
</template>
