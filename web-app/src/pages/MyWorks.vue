<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { request, session } from '../api/client'

const posts = ref([])
const loading = ref(false)
const error = ref('')
const hasMore = ref(true)
const busyId = ref(null)
const actionError = ref('')
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
  actionError.value = ''
  load()
}

async function transition(post, action) {
  if (busyId.value) return
  const message = action === 'publish' ? '确认提交审核？审核通过后才会公开。' : '确认撤回这篇作品？'
  if (!window.confirm(message)) return
  const current = revision
  busyId.value = post.post_id
  actionError.value = ''
  try {
    const result = await request(`/api/posts/${encodeURIComponent(post.post_id)}/${action}`, { method: 'POST' })
    if (current !== revision) return
    if (result.status !== (action === 'publish' ? 'PENDING' : 'WITHDRAWN')) throw new Error('作品状态响应不完整，请刷新确认')
    post.status = result.status
  } catch (e) {
    if (current === revision) actionError.value = e.message
  } finally {
    if (current === revision) busyId.value = null
  }
}

watch(() => session.generation, () => {
  revision++
  posts.value = []
  hasMore.value = false
  loading.value = false
  error.value = ''
  actionError.value = ''
  busyId.value = null
})
onMounted(load)
onBeforeUnmount(() => revision++)
</script>

<template>
  <section>
    <div class="feed-toolbar"><h1>我的作品</h1><div class="work-actions"><RouterLink to="/me/works/new">新建草稿</RouterLink><button class="secondary" :disabled="loading" @click="refresh">刷新</button></div></div>
    <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="quiet" @click="load">重试</button></p>
    <div v-if="loading && !posts.length" class="spinner">加载中…</div>
    <div v-else-if="!posts.length && !error" class="empty">还没有作品。</div>
    <ul v-else class="my-works-list">
      <li v-for="post in posts" :key="post.post_id" class="my-work-row">
        <div>
          <strong>{{ post.title || '（无标题）' }}</strong>
          <small>{{ statusLabels[post.status] || '状态未知' }}</small>
        </div>
        <div class="work-actions">
          <RouterLink v-if="post.status === 'PUBLISHED'" :to="`/post/${post.post_id}`">查看作品</RouterLink>
          <RouterLink v-if="['DRAFT', 'REJECTED', 'WITHDRAWN'].includes(post.status)" :to="`/me/works/${post.post_id}/edit`">继续编辑</RouterLink>
          <button v-if="['DRAFT', 'REJECTED', 'WITHDRAWN'].includes(post.status)" class="secondary" :disabled="!!busyId" @click="transition(post, 'publish')">提交审核</button>
          <button v-if="post.status === 'PENDING' || post.status === 'PUBLISHED'" class="danger" :disabled="!!busyId" @click="transition(post, 'withdraw')">撤回</button>
        </div>
      </li>
    </ul>
    <button v-if="hasMore && posts.length && !error" class="secondary more" :disabled="loading" @click="load">{{ loading ? '加载更多…' : '加载更多' }}</button>
  </section>
</template>
