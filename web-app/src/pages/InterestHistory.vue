<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { request, session } from '../api/client'

const items = ref([])
const cursor = ref(null)
const hasMore = ref(true)
const loading = ref(false)
const error = ref('')
let revision = 0

function reset() {
  revision++
  items.value = []
  cursor.value = null
  hasMore.value = true
  loading.value = false
  error.value = ''
}
async function load() {
  if (loading.value || !hasMore.value) return
  const current = revision
  loading.value = true
  error.value = ''
  const params = new URLSearchParams({ count: '20' })
  if (cursor.value) params.set('before_version', cursor.value)
  try {
    const result = await request(`/api/users/me/profile/history?${params}`)
    if (current !== revision) return
    if (result.user_id !== session.user?.id || !Array.isArray(result.items)) throw new Error('历史记录响应不完整')
    const known = new Set(items.value.map(item => item.version))
    items.value.push(...result.items.filter(item => !known.has(item.version)))
    hasMore.value = result.has_more === true && !!result.next_cursor && result.next_cursor !== cursor.value
    cursor.value = result.next_cursor
  } catch (e) {
    if (current === revision) error.value = e.message
  } finally {
    if (current === revision) loading.value = false
  }
}
watch(() => session.generation, () => { reset(); if (session.user) load() })
onMounted(load)
onBeforeUnmount(reset)
</script>

<template>
  <section>
    <div class="feed-toolbar"><h1>过往兴趣</h1><RouterLink to="/me/interests">返回兴趣偏好</RouterLink></div>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="quiet" @click="load">重试</button></p>
    <p v-if="loading && !items.length" class="spinner">加载中…</p>
    <p v-else-if="!items.length && !error" class="empty">还没有历史记录。</p>
    <ul class="my-works-list">
      <li v-for="item in items" :key="item.version" class="interest-history-row">
        <strong>{{ new Date(item.generated_at * 1000).toLocaleString() }}</strong>
        <p>{{ item.topics.length ? item.topics.map(topic => topic.name).join(' · ') : '暂无明显兴趣主题' }}</p>
        <p v-if="item.explanation" class="muted">{{ item.explanation }}</p>
      </li>
    </ul>
    <button v-if="hasMore && items.length && !error" class="secondary more" :disabled="loading" @click="load">{{ loading ? '加载更多…' : '加载更多' }}</button>
  </section>
</template>
