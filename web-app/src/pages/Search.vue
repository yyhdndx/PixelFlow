<script setup>
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePage } from '../api/page'
import { agentSearch, startSearch, stopSearch } from '../api/agentSearch'
import { session } from '../api/client'
import PostList from '../components/PostList.vue'
const route = useRoute()
const router = useRouter()
const query = computed(() => typeof route.query.q === 'string' ? route.query.q.trim() : '')
const page = usePage(() => query.value ? `/api/search/posts?count=20&q=${encodeURIComponent(query.value)}` : '')
const currentAnswer = computed(() => agentSearch.question === query.value && session.user ? agentSearch : null)
const busy = computed(() => currentAnswer.value && ['waiting', 'retrieving', 'preparing', 'generating', 'validating'].includes(currentAnswer.value.status))
const labels = { waiting: '等待中', retrieving: '正在检索作品', preparing: '正在准备回答', generating: '正在生成回答', validating: '正在核对回答', interrupted: '回答未完成', cancelled: '已停止', failed: '回答失败', done: '回答完成' }
function ask() {
  if (!session.user) { router.push({ path: '/login', query: { from: route.fullPath } }); return }
  startSearch(query.value)
}
watch(query, (next, previous) => {
  page.reset()
  if (previous && next !== previous && ['waiting', 'retrieving', 'preparing', 'generating', 'validating'].includes(agentSearch.status)) stopSearch()
  if (next) page.load()
})
onMounted(() => { if (query.value) page.load() })
</script>

<template>
  <section><h1>搜索</h1><p class="muted">{{ query ? `“${query}”的作品` : '在顶部输入关键词开始搜索。' }}</p>
    <div v-if="!query" class="search-discovery">
      <section>
        <h2>搜索记录</h2>
        <p class="muted">暂无搜索记录</p>
      </section>
      <section>
        <h2>试试搜索</h2>
        <p class="muted">示例关键词：摄影、旅行、插画</p>
      </section>
    </div>
    <div v-if="query" class="agent-answer">
      <div class="feed-toolbar"><h2>智能回答</h2><button v-if="busy" class="secondary" @click="stopSearch">停止</button><button v-else class="secondary" @click="ask">{{ currentAnswer ? '重新提问' : '生成回答' }}</button></div>
      <template v-if="currentAnswer">
        <p role="status">{{ labels[currentAnswer.status] }}</p>
        <p v-if="currentAnswer.error" class="error" role="alert">{{ currentAnswer.error }}</p>
        <p v-if="currentAnswer.answer" class="agent-answer-text">{{ currentAnswer.answer }}<span v-if="currentAnswer.status !== 'done'" class="muted">（未完成）</span></p>
        <ul v-if="currentAnswer.sources.length" class="agent-sources"><li v-for="source in currentAnswer.sources" :key="source.post_id"><RouterLink :to="`/post/${source.post_id}`">{{ source.title }}</RouterLink><span class="muted"> {{ source.author_name }}</span></li></ul>
      </template>
    </div>
    <PostList v-if="query" :page="page" empty="没有找到相关作品，试试其他关键词。" />
  </section>
</template>
