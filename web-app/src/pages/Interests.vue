<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { request, session, write } from '../api/client'

const profile = ref(null)
const preferences = ref(null)
const profileError = ref('')
const preferencesError = ref('')
const updating = ref(false)
let revision = 0

async function loadProfile() {
  const current = revision
  profileError.value = ''
  try {
    const result = await request('/api/users/me/profile')
    if (current === revision) profile.value = result
  } catch (e) {
    if (current === revision) profileError.value = e.message
  }
}
async function loadPreferences() {
  const current = revision
  preferencesError.value = ''
  try {
    const result = await request('/api/recommendations/me/preferences')
    if (current === revision) preferences.value = result
  } catch (e) {
    if (current === revision) preferencesError.value = e.message
  }
}
async function restoreTopic(topic) {
  if (updating.value || !preferences.value) return
  const current = revision
  updating.value = true
  preferencesError.value = ''
  try {
    const result = await write(`/api/users/me/profile/topics/${encodeURIComponent(topic)}`, 'PUT', { suppressed: false })
    if (current !== revision) return
    if (result.user_id !== session.user?.id || !Array.isArray(result.suppressed_topics)) throw new Error('偏好响应不完整，请刷新确认')
    preferences.value = result
    loadProfile()
  } catch (e) {
    if (current === revision) preferencesError.value = e.message
  } finally {
    if (current === revision) updating.value = false
  }
}
async function unblockAuthor(authorId) {
  if (updating.value || !preferences.value) return
  const current = revision
  updating.value = true
  preferencesError.value = ''
  try {
    const result = await write(`/api/recommendations/me/blocked-authors/${encodeURIComponent(authorId)}`, 'PUT', { blocked: false })
    if (current !== revision) return
    if (result.user_id !== session.user?.id || !Array.isArray(result.blocked_author_ids)) throw new Error('偏好响应不完整，请刷新确认')
    preferences.value = result
  } catch (e) {
    if (current === revision) preferencesError.value = e.message
  } finally {
    if (current === revision) updating.value = false
  }
}
async function togglePersonalized() {
  if (updating.value || !preferences.value) return
  const current = revision
  const next = !preferences.value.personalized
  updating.value = true
  preferencesError.value = ''
  try {
    const result = await write('/api/recommendations/me/preferences', 'PUT', { personalized: next })
    if (current !== revision) return
    if (result.user_id !== session.user?.id || result.personalized !== next) throw new Error('偏好响应不完整，请刷新确认')
    preferences.value = result
  } catch (e) {
    if (current === revision) preferencesError.value = e.message
  } finally {
    if (current === revision) updating.value = false
  }
}
function reset() {
  revision++
  profile.value = null
  preferences.value = null
  profileError.value = ''
  preferencesError.value = ''
  updating.value = false
}
watch(() => session.generation, () => { reset(); if (session.user) { loadProfile(); loadPreferences() } })
onMounted(() => { loadProfile(); loadPreferences() })
onBeforeUnmount(reset)
</script>

<template>
  <section class="content-page">
    <h1>兴趣偏好</h1>
    <div class="interest-settings">
      <p v-if="preferencesError" class="error" role="alert">偏好加载失败：{{ preferencesError }} <button v-if="!preferences" class="quiet" @click="loadPreferences">重试</button></p>
      <p v-if="!preferences && !preferencesError" class="spinner">加载偏好中…</p>
      <label v-if="preferences" class="interest-toggle"><input type="checkbox" :checked="preferences.personalized" :disabled="updating" @change="togglePersonalized"> 使用个性化推荐</label>
      <template v-if="preferences?.suppressed_topics?.length">
        <h2>减少推荐的主题</h2>
        <ul class="preference-list"><li v-for="topic in preferences.suppressed_topics" :key="topic">{{ topic }} <button class="quiet" :disabled="updating" @click="restoreTopic(topic)">恢复推荐</button></li></ul>
      </template>
      <template v-if="preferences?.blocked_author_ids?.length">
        <h2>不再推荐的作者</h2>
        <ul class="preference-list"><li v-for="authorId in preferences.blocked_author_ids" :key="authorId">{{ authorId }} <button class="quiet" :disabled="updating" @click="unblockAuthor(authorId)">解除</button></li></ul>
      </template>
    </div>
    <div class="feed-toolbar"><h2>我的兴趣</h2><RouterLink to="/me/interests/history">查看过往兴趣</RouterLink></div>
    <p v-if="profileError" class="error" role="alert">画像暂不可用：{{ profileError }} <button class="quiet" @click="loadProfile">重试</button></p>
    <p v-if="!profile && !profileError" class="spinner">加载兴趣中…</p>
    <p v-if="profile && !profile.topics?.length" class="empty">暂无兴趣画像。</p>
    <ul v-if="profile?.topics?.length" class="interest-topics"><li v-for="topic in profile.topics" :key="topic.name">{{ topic.name }}</li></ul>
  </section>
</template>
