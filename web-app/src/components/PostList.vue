<script setup>
import PostCard from './PostCard.vue'
defineProps({ page: Object, empty: { type: String, default: '暂时没有作品。' } })
</script>

<template>
  <div>
    <p v-if="page.error" class="error" role="alert">{{ page.error }} <button class="quiet" @click="page.load()">重试</button></p>
    <div v-if="page.loading && !page.items.length" class="spinner">加载中…</div>
    <div v-else-if="!page.items.length && !page.error" class="empty">{{ empty }}</div>
    <div class="waterfall"><PostCard v-for="post in page.items" :key="post.post_id" :post="post" /></div>
    <button v-if="page.hasMore && !page.error" class="secondary more" :disabled="page.loading" @click="page.load()">{{ page.loading ? '加载更多…' : '加载更多' }}</button>
  </div>
</template>
