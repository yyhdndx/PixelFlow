<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { session, logout } from './api/client'
const router = useRouter()
const route = useRoute()
const hadToken = ref(!!session.tokens.accessToken)
// 登录态失效（令牌过期刷新失败、退出）时回到登录页；游客浏览不受影响。
watch(() => session.tokens.accessToken, token => {
  if (token) { hadToken.value = true; return }
  if (hadToken.value) { hadToken.value = false; if (route.path !== '/login') router.replace('/login') }
})
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
      <div class="account" v-if="session.user">
        <span class="account-avatar" aria-hidden="true">{{ initial }}</span>
        <span class="account-meta">{{ session.user.nickname }}<small>@{{ session.user.username }}</small></span>
        <button class="quiet" @click="leave">退出</button>
      </div>
      <RouterLink v-else to="/login" class="login-link">登录 / 注册</RouterLink>
    </header>
    <main><RouterView :key="route.fullPath" /></main>
  </div>
</template>
