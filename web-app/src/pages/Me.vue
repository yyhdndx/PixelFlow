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
const avatarBusy = ref(false)
const avatarError = ref('')
const avatarNotice = ref('')
let revision = 0

async function selectAvatar(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || avatarBusy.value) return
  avatarError.value = ''
  avatarNotice.value = ''
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 8 * 1024 * 1024) {
    avatarError.value = '仅支持不超过 8 MB 的 JPEG 或 PNG 图片'
    return
  }
  const current = revision
  avatarBusy.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    const media = await request('/api/media', { method: 'POST', body: form })
    if (current !== revision) return
    if (!media.mediaId) throw new Error('图片上传响应不完整')
    const avatar = await write('/api/users/me/avatar', 'PUT', { mediaId: media.mediaId })
    if (current !== revision) return
    if (avatar.user_id !== session.user?.id || !avatar.avatar) throw new Error('头像更新响应不完整，请刷新后确认')
    session.user = { ...session.user, avatar: avatar.avatar }
    avatarNotice.value = '头像已更新'
  } catch (e) {
    if (current === revision) avatarError.value = e.message
  } finally {
    if (current === revision) avatarBusy.value = false
  }
}
async function clearAvatar() {
  if (avatarBusy.value || !session.user?.avatar) return
  const current = revision
  avatarBusy.value = true
  avatarError.value = ''
  avatarNotice.value = ''
  try {
    const avatar = await request('/api/users/me/avatar', { method: 'DELETE' })
    if (current !== revision) return
    if (avatar.user_id !== session.user?.id || avatar.avatar !== '') throw new Error('头像清除响应不完整，请刷新后确认')
    session.user = { ...session.user, avatar: '' }
    avatarNotice.value = '头像已清除'
  } catch (e) {
    if (current === revision) avatarError.value = e.message
  } finally {
    if (current === revision) avatarBusy.value = false
  }
}

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
  avatarBusy.value = false
  avatarError.value = ''
  avatarNotice.value = ''
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
    <div class="avatar-actions">
      <label>更换头像<input type="file" accept="image/jpeg,image/png" :disabled="avatarBusy" @change="selectAvatar"></label>
      <button v-if="session.user?.avatar" class="secondary" :disabled="avatarBusy" @click="clearAvatar">清除头像</button>
      <span v-if="avatarBusy" role="status">正在处理头像…</span>
    </div>
    <p v-if="avatarError" class="error" role="alert">{{ avatarError }}</p>
    <p v-if="avatarNotice" class="success" role="status">{{ avatarNotice }}</p>
    <div v-if="stats" class="profile-stats"><RouterLink to="/me/works">{{ stats.post_count }} 作品</RouterLink><RouterLink to="/me/followers">{{ stats.follower_count }} 粉丝</RouterLink><RouterLink to="/me/following">{{ stats.following_count }} 关注</RouterLink></div>
    <p v-if="statsError" class="error" role="alert">统计加载失败：{{ statsError }} <button class="quiet" @click="loadStats">重试</button></p>
    <form class="profile-edit" @submit.prevent="save">
      <h2>编辑资料</h2>
      <label>昵称<input v-model="nickname" maxlength="40" required></label>
      <label>简介<textarea v-model="bio" maxlength="60" rows="3"></textarea></label>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="notice" class="success" role="status">{{ notice }}</p>
      <button type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存资料' }}</button>
    </form>
    <nav class="profile-links"><RouterLink to="/me/works">我的作品 →</RouterLink><RouterLink to="/me/favorites">我的收藏 →</RouterLink><RouterLink to="/me/following">我的关注 →</RouterLink><RouterLink to="/me/notifications">通知 →</RouterLink><RouterLink to="/me/interests">兴趣偏好 →</RouterLink></nav>
  </section>
</template>
