<script setup>
import { ref, onMounted } from 'vue'
import { request } from '../../api/client'
const traces = ref([]), selected = ref(null)
const error = ref(''), detailError = ref('')
const loading = ref(false), detailLoading = ref(false), more = ref(false)
let cursor = null
const date = value => new Date(Number(value) * 1000).toLocaleString('zh-CN')
const question = value => value.length > 40 ? `${value.slice(0, 40)}…` : value
async function load(append = false) {
  loading.value = true; error.value = ''
  try {
    const query = append && cursor ? `?count=20&cursor=${cursor}` : '?count=20'
    const page = await request(`/api/admin/agent/traces${query}`)
    traces.value = append ? [...traces.value, ...page.traces] : page.traces
    cursor = page.next_cursor
    more.value = Boolean(cursor)
  } catch (e) { error.value = e.message }
  finally { loading.value = false }
}
async function open(trace) {
  selected.value = trace; detailError.value = ''
  detailLoading.value = true
  try { selected.value = (await request(`/api/admin/agent/traces/${trace.trace_id}`)).trace }
  catch (e) { detailError.value = e.message }
  finally { detailLoading.value = false }
}
onMounted(() => load())
</script>
<template>
  <header class="page-header"><div><div class="eyebrow">AGENT TRACES</div><h1>Agent 轨迹</h1><p class="muted">每次智能搜索的检索、模型调用与降级决策全链路留痕。</p></div><button class="secondary" :disabled="loading" @click="load()">刷新</button></header>
  <p v-if="error" role="alert" class="error">{{ error }}</p>
  <section class="panel">
    <h2>轨迹列表</h2>
    <p v-if="loading">正在加载…</p>
    <p v-else-if="!traces.length" class="empty">暂无 Agent 轨迹，先在客户端发起一次 AI 智能搜索。</p>
    <div v-else class="table-wrap"><table><thead><tr><th>问题</th><th>创建时间</th><th>耗时</th><th>模型提供方</th><th>状态</th><th>来源</th></tr></thead><tbody>
      <tr v-for="trace in traces" :key="trace.trace_id" class="trace-row" @click="open(trace)">
        <td>{{ question(trace.question) }}</td>
        <td>{{ date(trace.created_at) }}</td>
        <td>{{ trace.latency_ms }} ms</td>
        <td><span class="badge">{{ trace.provider || '—' }}</span></td>
        <td><span class="badge" :class="trace.degraded ? 'warn' : 'ok'">{{ trace.degraded ? '规则降级' : '模型回答' }}</span></td>
        <td>{{ trace.source_count }} 帖</td>
      </tr>
    </tbody></table></div>
    <button v-if="more" class="secondary" :disabled="loading" @click="load(true)">加载更多</button>
  </section>
  <section v-if="selected" class="panel" aria-label="轨迹详情">
    <div class="section-heading"><h2>{{ selected.question }}</h2><button class="quiet" @click="selected = null">关闭</button></div>
    <p v-if="detailLoading" class="muted">正在读取详情…</p>
    <p v-else-if="detailError" role="alert" class="error">{{ detailError }}</p>
    <template v-else>
      <p class="muted fine">会话 {{ selected.session_id || '—' }} · {{ date(selected.created_at) }} · 总耗时 {{ selected.latency_ms }} ms · {{ selected.provider || '规则' }}</p>
      <p v-if="selected.degraded" class="error">规则降级{{ selected.degraded_reason ? `：${selected.degraded_reason}` : '' }}</p>
      <h3>检索</h3>
      <p class="plain-text">查询词：{{ selected.retrieval?.query || '—' }} · 候选 {{ selected.retrieval?.candidate_count ?? 0 }} 帖 · 选用 {{ (selected.retrieval?.selected_ids || []).join('、') || '—' }}</p>
      <h3>模型</h3>
      <p class="plain-text">{{ selected.llm?.model || '—' }} · 调用耗时 {{ selected.llm?.latency_ms ?? 0 }} ms</p>
      <h3>综合回答</h3>
      <p class="plain-text">{{ selected.answer }}</p>
      <h3>引用来源</h3>
      <p v-if="!(selected.sources || []).length" class="muted">无引用</p>
      <ul v-else class="source-list"><li v-for="source in selected.sources" :key="source.post_id">作品 {{ source.post_id }} · {{ source.title }}</li></ul>
    </template>
  </section>
</template>
<style scoped>
.trace-row { cursor: pointer; }
.badge { display: inline-block; padding: 2px 8px; border-radius: 999px; background: var(--line, #e5e7eb); font-size: 12px; }
.badge.ok { background: #dcfce7; color: #166534; }
.badge.warn { background: #fef9c3; color: #854d0e; }
.source-list { margin: 0; padding-left: 20px; }
</style>
