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
const markingAll = ref(false)
const muted = ref([])
const draftMuted = ref([])
const settingsDialog = ref(null)
const preferencesReady = ref(false)
const preferencesBusy = ref(false)
const preferencesError = ref('')
const preferencesNotice = ref('')
let revision = 0
let settingsRevision = 0

async function loadPreferences() {
  const current = revision
  const settingsRequest = ++settingsRevision
  preferencesReady.value = false
  preferencesError.value = ''
  try {
    const result = await request('/api/users/me/notification-preferences')
    if (current !== revision || settingsRequest !== settingsRevision || !settingsDialog.value?.open) return
    if (!Array.isArray(result.muted_types)) throw new Error('通知设置响应不完整')
    muted.value = result.muted_types
    draftMuted.value = [...muted.value]
    preferencesReady.value = true
  } catch (e) {
    if (current === revision && settingsRequest === settingsRevision && settingsDialog.value?.open) preferencesError.value = e.message
  }
}
function openSettings() {
  settingsDialog.value.showModal()
  draftMuted.value = [...muted.value]
  preferencesNotice.value = ''
  loadPreferences()
}
function closeSettings() {
  if (preferencesBusy.value) return
  settingsRevision++
  settingsDialog.value?.close()
  draftMuted.value = [...muted.value]
}
function cancelSettings(event) {
  if (preferencesBusy.value) event.preventDefault()
  else { settingsRevision++; draftMuted.value = [...muted.value] }
}
async function savePreferences() {
  if (preferencesBusy.value || !preferencesReady.value) return
  const current = revision
  const selected = [...draftMuted.value]
  preferencesBusy.value = true
  preferencesError.value = ''
  try {
    const result = await write('/api/users/me/notification-preferences', 'PUT', { mutedTypes: selected })
    if (current !== revision || !settingsDialog.value?.open) return
    if (!Array.isArray(result.muted_types)) throw new Error('通知设置响应不完整，请刷新确认')
    muted.value = result.muted_types
    preferencesBusy.value = false
    closeSettings()
    preferencesNotice.value = '通知设置已保存'
    window.dispatchEvent(new Event('pixelflow:notifications-changed'))
    resetList()
    load()
  } catch (e) {
    if (current === revision) preferencesError.value = e.message
  } finally {
    if (current === revision) preferencesBusy.value = false
  }
}
function resetList() {
  revision++
  items.value = []
  cursor.value = null
  hasMore.value = false
  loading.value = false
  marking.value = ''
  markingAll.value = false
  error.value = ''
  markError.value = ''
}

function reset() {
  settingsRevision++
  settingsDialog.value?.close()
  resetList()
  muted.value = []
  draftMuted.value = []
  preferencesReady.value = false
  preferencesBusy.value = false
  preferencesError.value = ''
  preferencesNotice.value = ''
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
  if (item.read || marking.value || markingAll.value) return
  const current = revision
  marking.value = item.notification_id
  markError.value = ''
  try {
    const result = await write('/api/users/me/notifications/read', 'POST', { ids: [item.notification_id] })
    if (current === revision) {
      if (result.updated !== true) throw new Error('已读响应不完整')
      item.read = true
      window.dispatchEvent(new Event('pixelflow:notifications-changed'))
    }
  } catch (e) {
    if (current === revision) markError.value = e.message
  } finally {
    if (current === revision) marking.value = ''
  }
}
async function markAll() {
  if (marking.value || markingAll.value) return
  const current = revision
  markingAll.value = true
  markError.value = ''
  try {
    const result = await request('/api/users/me/notifications/read', { method: 'POST' })
    if (current !== revision) return
    if (result.updated !== true) throw new Error('已读响应不完整')
    items.value.forEach(item => { item.read = true })
    window.dispatchEvent(new Event('pixelflow:notifications-changed'))
  } catch (e) {
    if (current === revision) markError.value = e.message
  } finally {
    if (current === revision) markingAll.value = false
  }
}
watch(type, () => { resetList(); load() })
watch(() => session.generation, () => { reset(); if (session.user) load() })
onMounted(load)
onBeforeUnmount(reset)
</script>

<template>
  <section>
    <div class="feed-toolbar"><h1>消息通知</h1><div class="work-actions"><button class="secondary" @click="openSettings">通知设置</button><button class="secondary" :disabled="!!marking || markingAll || loading" @click="markAll">{{ markingAll ? '处理中…' : '全部已读' }}</button></div></div>
    <p v-if="preferencesNotice" class="success" role="status">{{ preferencesNotice }}</p>
    <dialog ref="settingsDialog" class="notification-dialog" aria-labelledby="notification-dialog-title" @cancel="cancelSettings">
      <form class="notification-settings" @submit.prevent="savePreferences">
        <h2 id="notification-dialog-title">通知设置</h2>
        <p v-if="!preferencesReady && !preferencesError" class="muted">加载设置中…</p>
        <template v-if="preferencesReady">
          <label v-for="option in types" :key="option.key"><input v-model="draftMuted" type="checkbox" :value="option.key" :disabled="preferencesBusy">屏蔽{{ option.label }}</label>
        </template>
        <p v-if="preferencesError" class="error" role="alert">{{ preferencesError }} <button v-if="!preferencesReady" type="button" class="quiet" @click="loadPreferences">重试</button></p>
        <div class="notification-dialog-actions"><button type="button" class="secondary" :disabled="preferencesBusy" @click="closeSettings">取消</button><button type="submit" :disabled="!preferencesReady || preferencesBusy">{{ preferencesBusy ? '保存中…' : '保存设置' }}</button></div>
      </form>
    </dialog>
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
        <button v-if="!item.read" class="secondary" :disabled="!!marking || markingAll" @click="mark(item)">标为已读</button><small v-else class="muted">已读</small>
      </li>
    </ul>
    <button v-if="hasMore && !error" class="secondary more" :disabled="loading" @click="load">{{ loading ? '加载更多…' : '加载更多' }}</button>
  </section>
</template>
