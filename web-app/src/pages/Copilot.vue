<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const back = computed(() => {
  if (route.params.screen === 'detail' && /^[1-9][0-9]*$/.test(route.params.id || '')) return `/post/${route.params.id}`
  if (route.params.screen === 'create' && /^[1-9][0-9]*$/.test(route.params.id || '')) return `/me/works/${route.params.id}/edit`
  if (route.params.screen === 'messages') return '/me/messages'
  return '/me'
})
</script>

<template>
  <section class="feature-shell content-page">
    <div class="feed-toolbar"><h1>创作助手</h1><RouterLink :to="back">返回</RouterLink></div>
    <div class="empty" role="status">创作助手暂未开放，当前无法生成回答或执行建议。</div>
  </section>
</template>
