<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const isHistory = computed(() => !route.params.screen)
const context = computed(() => ({ detail: '作品讨论', create: '创作建议', messages: '消息助手' })[route.params.screen] || '创作助手')
const back = computed(() => {
  if (route.params.screen === 'detail' && /^[1-9][0-9]*$/.test(route.params.id || '')) return `/post/${route.params.id}`
  if (route.params.screen === 'create' && /^[1-9][0-9]*$/.test(route.params.id || '')) return `/me/works/${route.params.id}/edit`
  if (route.params.screen === 'messages') return '/me/messages'
  return '/me'
})
</script>

<template>
  <section class="feature-shell content-page copilot-shell">
    <template v-if="isHistory">
      <div class="feed-toolbar"><h1>AI 助手</h1><button type="button" class="secondary" disabled>管理记录</button></div>
      <p class="muted">演示界面，暂不能生成或保存对话。</p>
      <div class="copilot-history-empty">
        <h2>对话记录</h2>
        <p>暂无对话记录</p>
        <p class="muted">从作品详情或草稿编辑页进入助手，可以查看对应的对话界面。</p>
      </div>
    </template>
    <template v-else>
      <div class="feed-toolbar"><div><h1>{{ context }}</h1><p class="muted">演示界面，暂不能生成或保存对话。</p></div><RouterLink to="/assistant">返回记录</RouterLink></div>
      <div class="copilot-conversation" aria-label="对话区">
        <p class="muted">暂无消息</p>
      </div>
      <div class="copilot-composer">
        <label for="copilot-input">输入内容</label>
        <div class="copilot-composer-row"><input id="copilot-input" placeholder="助手对话暂未开放" disabled><button type="button" disabled>发送</button></div>
      </div>
      <RouterLink :to="back" class="copilot-context-link">返回{{ context === '创作助手' ? '个人中心' : context === '作品讨论' ? '作品详情' : context === '创作建议' ? '草稿编辑' : '消息' }}</RouterLink>
    </template>
  </section>
</template>
