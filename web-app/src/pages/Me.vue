<script setup>
import { session } from '../api/client'
</script>

<template>
  <section>
    <h1>个人中心</h1>
    <div class="profile-head">
      <span class="profile-avatar"><img v-if="session.user?.avatar" :src="session.user.avatar" alt="头像"><template v-else>{{ (session.user?.nickname || 'U').charAt(0) }}</template></span>
      <h2>{{ session.user?.nickname }}</h2>
    </div>
    <nav class="profile-links"><RouterLink to="/me/works">我的作品 →</RouterLink><RouterLink to="/me/favorites">我的收藏 →</RouterLink><RouterLink to="/me/following">我的关注 →</RouterLink></nav>
  </section>
</template>
