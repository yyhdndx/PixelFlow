<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { request, session, logout } from './api/client'
import { agentSearch, stopSearch } from './api/agentSearch'
import { prepareCreation, clearCreation } from './api/creationEntry'
const router = useRouter()
const route = useRoute()
const hadToken = ref(!!session.tokens.accessToken)
// 登录态失效（令牌过期刷新失败、退出）时回到登录页；游客浏览不受影响。
watch(() => session.tokens.accessToken, token => {
  if (token) { hadToken.value = true; return }
  if (hadToken.value) { hadToken.value = false; if (route.path !== '/login') router.replace({ path: '/login', query: { from: route.fullPath } }) }
})
const search = ref(typeof route.query.q === 'string' ? route.query.q : '')
watch(() => route.query.q, q => { search.value = typeof q === 'string' ? q : '' })
function submitSearch() {
  const q = search.value.trim()
  if (q) {
    if (q !== agentSearch.question && ['waiting', 'retrieving', 'preparing', 'generating', 'validating'].includes(agentSearch.status)) stopSearch()
    router.push({ path: '/search', query: { q } })
  }
}
const initial = computed(() => (session.user?.nickname || session.user?.username || '游').trim().charAt(0).toUpperCase())
const createOpen = ref(false)
const createMenu = ref(null)
const createTrigger = ref(null)
const galleryInput = ref(null)
const cameraInput = ref(null)
const unreadCount = ref(null)
let unreadRevision = 0
async function loadUnread() {
  const current = ++unreadRevision
  unreadCount.value = null
  if (!session.user) return
  try {
    const result = await request('/api/users/me/notifications/unread-count')
    if (current === unreadRevision) unreadCount.value = Number.isSafeInteger(result.count) && result.count >= 0 ? result.count : null
  } catch { if (current === unreadRevision) unreadCount.value = null }
}
function closeCreate(event) {
  if (event?.type === 'keydown' && event.key !== 'Escape') return
  if (event?.type === 'pointerdown' && createMenu.value?.contains(event.target)) return
  if (event?.type === 'keydown' && createOpen.value && createMenu.value?.contains(document.activeElement)) createTrigger.value?.focus()
  createOpen.value = false
}
async function openCreate() {
  if (!session.user) { router.push({ path: '/login', query: { from: '/me/works/new' } }); return }
  createOpen.value = !createOpen.value
  if (createOpen.value) { await nextTick(); createMenu.value?.querySelector('.create-options button')?.focus() }
}
function startCreate(mode) {
  if (!session.user) return
  createOpen.value = false
  if (mode === 'gallery') galleryInput.value?.click()
  else if (mode === 'camera') cameraInput.value?.click()
  else router.push({ path: '/me/works/new', query: { from: route.fullPath } })
}
function selectedForCreate(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  if (!files.length) return
  prepareCreation(files, session.generation, route.path === '/me/works/new' ? '/' : route.fullPath)
  if (route.path === '/me/works/new') window.dispatchEvent(new Event('pixelflow:creation-selected'))
  else router.push('/me/works/new')
}
watch(() => session.generation, () => { clearCreation(); createOpen.value = false; loadUnread() })
watch(() => route.fullPath, (next, previous) => { createOpen.value = false; if (next.startsWith('/me/notifications') || previous.startsWith('/me/notifications')) loadUnread() })
onMounted(() => { document.addEventListener('pointerdown', closeCreate); document.addEventListener('keydown', closeCreate); window.addEventListener('pixelflow:notifications-changed', loadUnread); loadUnread() })
onBeforeUnmount(() => { document.removeEventListener('pointerdown', closeCreate); document.removeEventListener('keydown', closeCreate); window.removeEventListener('pixelflow:notifications-changed', loadUnread) })
async function leave() { try { await logout() } catch {} }
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <RouterLink to="/" class="brand">
        <span class="brand-mark" aria-hidden="true">
          <svg width="19" height="19" viewBox="0 0 20 20" fill="none">
            <rect x="2" y="2" width="7" height="7" rx="2" fill="#fff" opacity=".95"/>
            <rect x="11" y="2" width="7" height="7" rx="3.5" fill="#fff" opacity=".65"/>
            <rect x="2" y="11" width="7" height="7" rx="3.5" fill="#fff" opacity=".65"/>
            <rect x="11" y="11" width="7" height="7" rx="2" fill="#fff" opacity=".95"/>
          </svg>
        </span>
        <span class="brand-name">PixelFlow<small>创作社区</small></span>
      </RouterLink>
      <form class="top-search" role="search" @submit.prevent="submitSearch"><input v-model="search" aria-label="搜索作品" placeholder="搜索标题或话题" maxlength="60"><button type="submit">搜索</button></form>
      <nav class="primary-nav" aria-label="主导航">
        <RouterLink to="/">首页</RouterLink>
        <RouterLink to="/assistant">AI</RouterLink>
        <div ref="createMenu" class="create-menu">
          <button ref="createTrigger" type="button" class="create-trigger" :aria-expanded="createOpen" aria-controls="create-options" @click="openCreate">＋ 创作</button>
          <div v-if="createOpen" id="create-options" class="create-options" aria-label="选择创作方式">
            <button type="button" @click="startCreate('camera')">拍摄新作品</button>
            <button type="button" @click="startCreate('gallery')">从相册创建</button>
            <button type="button" @click="startCreate('blank')">创建空白作品</button>
          </div>
          <input ref="cameraInput" class="visually-hidden" type="file" accept="image/jpeg,image/png" capture="environment" tabindex="-1" aria-label="拍摄新作品" @change="selectedForCreate">
          <input ref="galleryInput" class="visually-hidden" type="file" accept="image/jpeg,image/png" multiple tabindex="-1" aria-label="从相册创建" @change="selectedForCreate">
        </div>
        <RouterLink to="/me/notifications" class="nav-messages">消息<span v-if="unreadCount > 0" class="nav-badge" :aria-label="`${unreadCount} 条未读通知`">{{ unreadCount > 99 ? '99+' : unreadCount }}</span></RouterLink>
      </nav>
      <div class="account" v-if="session.user">
        <RouterLink to="/me" class="account-link"><span class="account-avatar" aria-hidden="true">{{ initial }}</span>
        <span class="account-meta">{{ session.user.nickname }}<small>个人中心</small></span></RouterLink>
        <button class="quiet" @click="leave">退出</button>
      </div>
      <RouterLink v-else to="/login" class="login-link">登录 / 注册</RouterLink>
    </header>
    <main><RouterView :key="route.fullPath" /></main>
  </div>
</template>
