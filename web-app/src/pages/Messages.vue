<script setup>
import { useRoute } from 'vue-router'

const route = useRoute()
const conversationId = typeof route.params.id === 'string' && /^[1-9][0-9]*$/.test(route.params.id) ? route.params.id : ''
</script>

<template>
  <section class="feature-shell content-page">
    <div class="feed-toolbar"><h1>{{ conversationId ? '私信会话' : '私信' }}</h1><RouterLink :to="conversationId ? '/me/messages' : '/me'">{{ conversationId ? '返回私信' : '返回个人中心' }}</RouterLink></div>
    <div class="empty" role="status">私信功能暂未开放，当前无法查看会话或发送消息。</div>
    <p><RouterLink to="/assistant/messages">消息助手（暂未开放）</RouterLink></p>
  </section>
</template>
