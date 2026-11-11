<script setup>
import { ref, onMounted, watch } from 'vue'
import { request, session } from '../api/client'

const stats = ref(null)
const statsError = ref('')
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
onMounted(loadStats)
watch(() => session.generation, () => {
  revision++
  stats.value = null
  statsError.value = ''
  if (session.user) loadStats()
})
</script>

<template>
  <section class="profile-page">
    <h1>个人中心</h1>
    <div class="profile-overview">
      <div class="profile-head">
        <span class="profile-avatar"><img v-if="session.user?.avatar" :src="session.user.avatar" alt="头像"><template v-else>{{ (session.user?.nickname || 'U').charAt(0) }}</template></span>
        <h2>{{ session.user?.nickname }}</h2>
        <p class="profile-bio">{{ session.user?.bio || '还没有填写简介' }}</p>
        <RouterLink to="/me/edit" class="profile-edit-link">编辑资料</RouterLink>
      </div>
      <nav v-if="stats" class="profile-stats" aria-label="个人统计">
        <RouterLink to="/me/works"><strong>{{ stats.post_count }}</strong><span>作品</span></RouterLink>
        <RouterLink to="/me/followers"><strong>{{ stats.follower_count }}</strong><span>粉丝</span></RouterLink>
        <RouterLink to="/me/following"><strong>{{ stats.following_count }}</strong><span>关注</span></RouterLink>
      </nav>
    </div>
    <p v-if="statsError" class="error" role="alert">统计加载失败：{{ statsError }} <button class="quiet" @click="loadStats">重试</button></p>
    <h2 class="profile-section-title">我的空间</h2>
    <nav class="profile-links" aria-label="我的空间">
      <RouterLink to="/me/interests"><span>兴趣偏好</span><span aria-hidden="true">›</span></RouterLink>
      <RouterLink to="/me/favorites"><span>我的收藏</span><span aria-hidden="true">›</span></RouterLink>
    </nav>
    <h2 class="profile-section-title">消息</h2>
    <nav class="profile-links" aria-label="消息">
      <RouterLink to="/me/notifications"><span>通知</span><span aria-hidden="true">›</span></RouterLink>
      <RouterLink to="/me/messages"><span>私信（暂未开放）</span><span aria-hidden="true">›</span></RouterLink>
    </nav>
  </section>
</template>
