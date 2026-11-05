<script setup>
import { onMounted } from 'vue'
import { usePage } from '../api/page'
const page = usePage(() => '/api/users/me/following?count=20', 'user_list', 'user_id')
onMounted(() => page.load())
</script>

<template>
  <section><h1>我的关注</h1>
    <p v-if="page.error" class="error" role="alert">{{ page.error }} <button class="quiet" @click="page.load()">重试</button></p>
    <p v-if="page.loading && !page.items.length" class="spinner">加载中…</p>
    <p v-else-if="!page.items.length && !page.error" class="empty">还没有关注的作者。</p>
    <div class="profile-links"><RouterLink v-for="user in page.items" :key="user.user_id" :to="`/author/${user.user_id}`"><span class="avatar"><img v-if="user.avatar" :src="user.avatar" alt=""><template v-else>{{ (user.nickname || 'U').charAt(0) }}</template></span>{{ user.nickname }} →</RouterLink></div>
    <button v-if="page.hasMore && !page.error" class="secondary more" :disabled="page.loading" @click="page.load()">{{ page.loading ? '加载更多…' : '加载更多' }}</button>
  </section>
</template>
