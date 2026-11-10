<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { request, session, write } from '../api/client'

const types = [{ key: 'MENTION', label: '@我' }, { key: 'REPLY', label: '回复' }, { key: 'LIKE', label: '赞' }]
const type = ref('MENTION')
const items = ref([])
const cursor = ref(null)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')
const markError = ref('')
const marking = ref('')
let revision = 0

function reset() {
  revision++
  items.value = []
  cursor.value = null
  hasMore.value = false
  loading.value = false
  marking.value = ''
  error.value = ''
  markError.value = ''
}
async function load() {
  if (loading.value) return
  const current = revision
  loading.value = true
  error.value = ''
  const params = new URLSearchParams({ type: type.value, count: '20' })
  if (cursor.value) params.set('cursor', cursor.value)
  try {
    const result = await request(`/api/users/me/notifications?${params}`)
    if (current !== revision) return
    const seen = new Set(items.value.map(item => item.notification_id))
    items.value.push(...result.notification_list.filter(item => !seen.has(item.notification_id)))
    cursor.value = result.next_cursor
    hasMore.value = result.has_more === 1 && !!result.next_cursor
  } catch (e) {
    if (current === revision) error.value = e.message
  } finally {
    if (current === revision) loading.value = false
  }
}
async function mark(item) {
  if (item.read || marking.value) return
  const current = revision
  marking.value = item.notification_id
  markError.value = ''
  try {
    const result = await write('/api/users/me/notifications/read', 'POST', { ids: [item.notification_id] })
    if (current === revision) {
      if (result.updated !== true) throw new Error('已读响应不完整')
      item.read = true
    }
  } catch (e) {
    if (current === revision) markError.value = e.message
  } finally {
    if (current === revision) marking.value = ''
  }
}
watch(type, () => { reset(); load() })
watch(() => session.generation, reset)
onMounted(load)
onBeforeUnmount(reset)
</script>

<template>
  <section>
    <h1>通知</h1>
    <div class="notification-tabs" role="group" aria-label="通知类型">
      <button v-for="option in types" :key="option.key" class="secondary" :aria-pressed="type === option.key" @click="type = option.key">{{ option.label }}</button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="quiet" @click="load">重试</button></p>
    <p v-if="markError" class="error" role="alert">标为已读失败：{{ markError }}</p>
    <p v-if="loading && !items.length" class="spinner">加载中…</p>
    <p v-else-if="!items.length && !error" class="empty">暂无通知。</p>
    <ul class="my-works-list">
      <li v-for="item in items" :key="item.notification_id" class="my-work-row">
        <div><strong>{{ item.actor.nickname }}</strong> {{ item.type === 'LIKE' ? '赞了你的作品' : item.type === 'REPLY' ? '回复了你' : '提到了你' }} <RouterLink :to="`/post/${item.post_id}`">{{ item.post_title || '查看作品' }}</RouterLink></div>
        <button v-if="!item.read" class="secondary" :disabled="!!marking" @click="mark(item)">标为已读</button><small v-else class="muted">已读</small>
      </li>
    </ul>
    <button v-if="hasMore && !error" class="secondary more" :disabled="loading" @click="load">{{ loading ? '加载更多…' : '加载更多' }}</button>
  </section>
</template>
