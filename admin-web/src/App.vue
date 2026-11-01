<script setup>
import { watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { session, logout } from './api/client'
const router = useRouter()
const route = useRoute()
watch(() => session.generation, () => { if (!session.tokens.accessToken) router.replace('/login') })
async function leave() { try { await logout() } catch {} router.replace('/login') }
</script>

<template>
  <div v-if="route.path !== '/login'" class="shell">
    <aside>
      <div class="brand">PixelFlow <small>管理工作台</small></div>
      <nav aria-label="主导航">
        <RouterLink to="/moderation">作品审核</RouterLink>
        <RouterLink to="/users">用户管理</RouterLink>
        <RouterLink to="/agent-traces">Agent 轨迹</RouterLink>
      </nav>
      <div class="account"><span>{{ session.user?.nickname }}</span><button class="quiet" @click="leave">退出登录</button></div>
    </aside>
    <main><RouterView :key="session.generation" /></main>
  </div>
  <RouterView v-else />
</template>
