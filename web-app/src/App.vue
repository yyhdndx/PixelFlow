<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { session, logout } from './api/client'
import { agentSearch, stopSearch } from './api/agentSearch'
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
