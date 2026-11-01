<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../../api/client'
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const router = useRouter()
async function submit() {
  busy.value = true; error.value = ''
  try { await login(username.value, password.value); await router.replace('/moderation') }
  catch (e) { error.value = e.message }
  finally { busy.value = false; password.value = '' }
}
</script>
<template>
  <main class="login-screen"><section class="login-panel">
    <div class="eyebrow">PIXELFLOW · ADMIN</div><h1>登录管理工作台</h1>
    <p class="muted">审核创作内容，维护社区秩序。</p>
    <form @submit.prevent="submit">
      <label>管理员账号<input v-model="username" autocomplete="username" required maxlength="32"></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required maxlength="72"></label>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <button :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
    </form>
    <p class="muted fine">仅向管理员开放，操作由服务端鉴权并记录。</p>
  </section></main>
</template>
