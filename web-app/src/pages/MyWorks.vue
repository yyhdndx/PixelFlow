<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { request, session } from '../api/client'

const posts = ref([])
const loading = ref(false)
const error = ref('')
const hasMore = ref(true)
let revision = 0

const statusLabels = {
  DRAFT: '草稿',
  PENDING: '待审核',
  PUBLISHED: '已发布',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
}

async function load() {
  if (loading.value || !hasMore.value) return
  const current = revision
  const cursor = posts.value.at(-1)?.post_id
  loading.value = true
  error.value = ''
  try {
    const result = await request(`/api/posts?count=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
    if (current !== revision) return
    if (!Array.isArray(result)) throw new Error('作品列表格式不正确')
    const seen = new Set(posts.value.map(post => post.post_id))
    posts.value.push(...result.filter(post => {
      if (seen.has(post.post_id)) return false
      seen.add(post.post_id)
      return true
    }))
    hasMore.value = result.length > 0
  } catch (e) {
    if (current === revision) error.value = e.message
  } finally {
    if (current === revision) loading.value = false
  }
}

function refresh() {
  revision++
  posts.value = []
  hasMore.value = true
  loading.value = false
  error.value = ''
  load()
}

watch(() => session.generation, () => {
  revision++
  posts.value = []
  hasMore.value = false
  loading.value = false
  error.value = ''
})
onMounted(load)
onBeforeUnmount(() => revision++)
</script>

<template>
  <section>
    <div class="feed-toolbar"><h1>我的作品</h1><button class="secondary" :disabled="loading" @click="refresh">刷新</button></div>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="quiet" @click="load">重试</button></p>
    <div v-if="loading && !posts.length" class="spinner">加载中…</div>
    <div v-else-if="!posts.length && !error" class="empty">还没有作品。</div>
    <ul v-else class="my-works-list">
      <li v-for="post in posts" :key="post.post_id" class="my-work-row">
        <div>
          <strong>{{ post.title || '（无标题）' }}</strong>
          <small>{{ statusLabels[post.status] || '状态未知' }}</small>
        </div>
        <RouterLink v-if="post.status === 'PUBLISHED'" :to="`/post/${post.post_id}`">查看作品</RouterLink>
        <RouterLink v-else-if="['DRAFT', 'REJECTED', 'WITHDRAWN'].includes(post.status)" :to="`/me/works/${post.post_id}/edit`">继续编辑</RouterLink>
      </li>
    </ul>
    <button v-if="hasMore && posts.length && !error" class="secondary more" :disabled="loading" @click="load">{{ loading ? '加载更多…' : '加载更多' }}</button>
  </section>
</template>
