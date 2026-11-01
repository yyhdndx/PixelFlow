<script setup>
import { computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { session, logout } from './api/client'
const router = useRouter()
const route = useRoute()
watch(() => session.generation, () => { if (!session.tokens.accessToken) router.replace('/login') })
const initial = computed(() => (session.user?.nickname || session.user?.username || 'A').trim().charAt(0).toUpperCase())
async function leave() { try { await logout() } catch {} router.replace('/login') }
</script>

<template>
  <div v-if="route.path !== '/login'" class="shell">
    <aside>
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">
          <svg width="19" height="19" viewBox="0 0 20 20" fill="none">
            <rect x="2" y="2" width="7" height="7" rx="2" fill="#fff" opacity=".95"/>
            <rect x="11" y="2" width="7" height="7" rx="3.5" fill="#fff" opacity=".65"/>
            <rect x="2" y="11" width="7" height="7" rx="3.5" fill="#fff" opacity=".65"/>
            <rect x="11" y="11" width="7" height="7" rx="2" fill="#fff" opacity=".95"/>
          </svg>
        </span>
        <span class="brand-name">PixelFlow<small>管理工作台</small></span>
      </div>
      <nav aria-label="主导航">
        <RouterLink to="/moderation">
          <svg width="16" height="16" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M10 2.5 3.5 5v4.7c0 4 2.8 6.7 6.5 7.8 3.7-1.1 6.5-3.8 6.5-7.8V5L10 2.5Z"/><path d="m7.2 9.8 2 2 3.6-3.8"/></svg>
          作品审核
        </RouterLink>
        <RouterLink to="/users">
          <svg width="16" height="16" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="7.5" cy="6.5" r="3"/><path d="M2.5 16.5c0-2.8 2.2-4.5 5-4.5s5 1.7 5 4.5"/><circle cx="14.5" cy="7.5" r="2.4"/><path d="M14 12.3c2.4.3 3.9 1.8 3.9 4.2"/></svg>
          用户管理
        </RouterLink>
        <RouterLink to="/agent-traces">
          <svg width="16" height="16" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="4.5" cy="10" r="2.2"/><circle cx="15.5" cy="4.8" r="2.2"/><circle cx="15.5" cy="15.2" r="2.2"/><path d="M6.5 9 13.4 5.6M6.5 11 13.4 14.4"/></svg>
          Agent 轨迹
        </RouterLink>
      </nav>
      <div class="account">
        <span class="account-avatar" aria-hidden="true">{{ initial }}</span>
        <span class="account-meta">{{ session.user?.nickname }}<small>@{{ session.user?.username }}</small></span>
        <button class="quiet" @click="leave">退出登录</button>
      </div>
    </aside>
    <main><RouterView :key="session.generation" /></main>
  </div>
  <RouterView v-else />
</template>
