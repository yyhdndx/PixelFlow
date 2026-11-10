<script setup>
import { ref, onMounted, watch } from 'vue'
import { request, session, write } from '../api/client'

const nickname = ref('')
const bio = ref('')
const stats = ref(null)
const statsError = ref('')
const error = ref('')
const notice = ref('')
const saving = ref(false)
let revision = 0

async function loadStats() {
  const current = revision
  statsError.value = ''
  try {
    const data = await request('/api/users/me/stats')
    if (current === revision) stats.value = data
  } catch (e) {
    if (current === revision) statsError.value = e.message
  }
}
function sync() {
  nickname.value = session.user?.nickname || ''
  bio.value = session.user?.bio || ''
}
async function save() {
  if (saving.value) return
  const name = nickname.value.trim()
  if (!name || name.length > 40 || bio.value.length > 60) { error.value = '昵称需为 1–40 个字符，简介不超过 60 个字符'; return }
  const current = revision
  saving.value = true
  error.value = ''
  notice.value = ''
  const description = bio.value
  try {
    const updated = await write('/api/users/me', 'PUT', { nickname: name, bio: description })
    if (current !== revision) return
    session.user = updated
    if (nickname.value === name && bio.value === description) notice.value = '资料已保存'
    else notice.value = '之前的修改已保存，当前仍有未保存的改动'
  } catch (e) {
    if (current === revision) error.value = e.message
  } finally {
    if (current === revision) saving.value = false
  }
}
onMounted(() => { sync(); loadStats() })
watch(() => session.generation, () => {
  revision++
  stats.value = null
  statsError.value = ''
  notice.value = ''
  error.value = ''
  saving.value = false
  sync()
  if (session.user) loadStats()
})
</script>

<template>
  <section>
    <h1>个人中心</h1>
    <div class="profile-head">
      <span class="profile-avatar"><img v-if="session.user?.avatar" :src="session.user.avatar" alt="头像"><template v-else>{{ (session.user?.nickname || 'U').charAt(0) }}</template></span>
      <div><h2>{{ session.user?.nickname }}</h2><p v-if="session.user?.bio" class="profile-bio">{{ session.user.bio }}</p></div>
    </div>
    <div v-if="stats" class="profile-stats"><RouterLink to="/me/works">{{ stats.post_count }} 作品</RouterLink><span>{{ stats.follower_count }} 粉丝</span><RouterLink to="/me/following">{{ stats.following_count }} 关注</RouterLink></div>
    <p v-if="statsError" class="error" role="alert">统计加载失败：{{ statsError }} <button class="quiet" @click="loadStats">重试</button></p>
    <form class="profile-edit" @submit.prevent="save">
      <h2>编辑资料</h2>
      <label>昵称<input v-model="nickname" maxlength="40" required></label>
      <label>简介<textarea v-model="bio" maxlength="60" rows="3"></textarea></label>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="notice" class="success" role="status">{{ notice }}</p>
      <button type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存资料' }}</button>
    </form>
    <nav class="profile-links"><RouterLink to="/me/works">我的作品 →</RouterLink><RouterLink to="/me/favorites">我的收藏 →</RouterLink><RouterLink to="/me/following">我的关注 →</RouterLink></nav>
  </section>
</template>
