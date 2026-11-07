<script setup>
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { usePage } from '../api/page'
import PostList from '../components/PostList.vue'
const route = useRoute()
const query = computed(() => typeof route.query.q === 'string' ? route.query.q.trim() : '')
const page = usePage(() => query.value ? `/api/search/posts?count=20&q=${encodeURIComponent(query.value)}` : '')
onMounted(() => page.load())
</script>

<template>
  <section><h1>搜索</h1><p class="muted">{{ query ? `“${query}”的作品` : '在顶部输入关键词开始搜索。' }}</p>
    <PostList v-if="query" :page="page" empty="没有找到相关作品，试试其他关键词。" />
  </section>
</template>
