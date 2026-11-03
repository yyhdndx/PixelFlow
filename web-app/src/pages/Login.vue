<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { login, register } from '../api/client'
const router = useRouter()
const route = useRoute()
const mode = ref('login')
const username = ref('')
const password = ref('')
const nickname = ref('')
const busy = ref(false)
const error = ref('')
async function submit() {
  busy.value = true; error.value = ''
  try {
    if (mode.value === 'login') await login(username.value, password.value)
    else await register(username.value, password.value, nickname.value)
    await router.replace(route.query.from || '/')
  } catch (e) { error.value = e.message }
  finally { busy.value = false; password.value = '' }
}
</script>

<template>
  <main class="login-screen"><section class="login-panel">
    <div class="login-brand">
      <span class="brand-mark" aria-hidden="true">
        <svg width="19" height="19" viewBox="0 0 20 20" fill="none">
          <rect x="2" y="2" width="7" height="7" rx="2" fill="#fff" opacity=".95"/>
          <rect x="11" y="2" width="7" height="7" rx="3.5" fill="#fff" opacity=".65"/>
          <rect x="2" y="11" width="7" height="7" rx="3.5" fill="#fff" opacity=".65"/>
          <rect x="11" y="11" width="7" height="7" rx="2" fill="#fff" opacity=".95"/>
        </svg>
      </span>
      <span class="brand-name">PixelFlow<small>创作社区</small></span>
    </div>
    <h1>{{ mode === 'login' ? '登录' : '注册' }}</h1>
    <p class="muted">浏览、点赞、收藏与评论社区作品。</p>
    <form @submit.prevent="submit">
      <label v-if="mode === 'register'">昵称<input v-model="nickname" required maxlength="40" placeholder="展示昵称"></label>
      <label>账号<input v-model="username" autocomplete="username" required maxlength="32" placeholder="3–32 位字母数字下划线"></label>
      <label>密码<input v-model="password" type="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" required maxlength="72" placeholder="至少 10 位"></label>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <button :disabled="busy">{{ busy ? '请稍候…' : (mode === 'login' ? '登录' : '注册并登录') }}</button>
    </form>
    <p class="mode-switch">
      {{ mode === 'login' ? '还没有账号？' : '已有账号？' }}
      <a @click="mode = mode === 'login' ? 'register' : 'login'; error = ''">{{ mode === 'login' ? '去注册' : '去登录' }}</a>
    </p>
    <p class="mode-switch"><RouterLink to="/">先逛逛，暂不登录</RouterLink></p>
  </section></main>
</template>
