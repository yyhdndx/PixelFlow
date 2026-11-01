<script setup>
import { ref, onMounted } from 'vue'
import { request, write } from '../../api/client'
import PostMedia from '../../components/PostMedia.vue'
const posts = ref([]), records = ref([]), selected = ref(null)
const reason = ref(''), error = ref(''), notice = ref('')
const loading = ref(false), saving = ref(false), more = ref(false), auditLoading = ref(false)
const suggestion = ref(null), suggesting = ref(false), suggestionError = ref('')
let auditCursor = '0'
const labels = { DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已通过', REJECTED: '已驳回', WITHDRAWN: '已撤回' }
const riskLabels = { low: '低风险', medium: '中风险', high: '高风险' }
const actionLabels = { approve: '建议通过', review: '建议人工复核', reject: '建议驳回' }
const riskClass = { low: '', medium: 'pending', high: 'disabled' }
const actionClass = { approve: '', review: 'pending', reject: 'disabled' }
const date = value => new Date(Number(value) * 1000).toLocaleString('zh-CN')
async function load(append = false) {
  loading.value = true; error.value = ''
  try {
    const cursor = append && posts.value.length ? `&cursor=${posts.value.at(-1).post_id}` : ''
    const rows = await request(`/api/admin/posts?status=PENDING&count=20${cursor}`)
    posts.value = append ? [...posts.value, ...rows] : rows
    more.value = rows.length === 20
  } catch (e) { error.value = e.message }
  finally { loading.value = false }
}
async function audit() {
  if (auditLoading.value) return
  auditLoading.value = true
  try {
    let rows
    do {
      rows = await request(`/api/admin/moderation?count=50&after=${auditCursor}`)
      if (rows.length) {
        auditCursor = String(rows.at(-1).id)
        records.value = [...rows].reverse().concat(records.value)
      }
    } while (rows.length === 50)
  } catch (e) { error.value = e.message }
  finally { auditLoading.value = false }
}
function open(post) {
  selected.value = post; reason.value = ''; notice.value = ''; error.value = ''
  suggestion.value = null; suggestionError.value = ''
}
async function loadSuggestion() {
  if (suggesting.value || !selected.value) return
  suggesting.value = true; suggestionError.value = ''
  try {
    suggestion.value = (await write('/api/admin/agent/moderation-suggestion', 'POST',
      { post_id: selected.value.post_id })).suggestion
  } catch (e) { suggestionError.value = e.message }
  finally { suggesting.value = false }
}
function applySuggestion() { if (suggestion.value) reason.value = suggestion.value.reason }
async function decide(status) {
  if (saving.value || !reason.value.trim() || reason.value.length > 500) return
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const result = await write(`/api/admin/posts/${selected.value.post_id}/moderation`, 'POST', { status, reason: reason.value })
    notice.value = `作品 ${result.postId}：${labels[result.status]}`
    selected.value = null
    await Promise.all([load(), audit()])
  } catch (e) {
    if (e.status === 409) {
      selected.value = null
      await Promise.all([load(), audit()])
    }
    error.value = e.message
  }
  finally { saving.value = false }
}
onMounted(() => { load(); audit() })
</script>
<template>
  <header class="page-header"><div><div class="eyebrow">CONTENT REVIEW</div><h1>作品审核</h1><p class="muted">审核待发布作品，每次决定均由服务端留痕。</p></div><button class="secondary" :disabled="loading || saving" @click="load(); audit()">刷新</button></header>
  <p v-if="error" role="alert" class="error">{{ error }}</p><p v-if="notice" role="status" class="success">{{ notice }}</p>
  <section class="panel">
    <h2>待审核队列</h2><p class="muted fine">提交审核时间暂未由接口提供，以“—”展示。</p>
    <p v-if="loading">正在加载…</p>
    <p v-else-if="!posts.length" class="empty">暂无待审核作品</p>
    <div v-for="post in posts" :key="post.post_id" class="post-row">
      <PostMedia :clips="post.clips" compact />
      <div class="post-copy"><h3>{{ post.title || '未命名作品' }}</h3><p>{{ post.author.nickname || '未设置昵称' }}</p><p class="muted fine">作品 {{ post.post_id }} · 提交时间 —</p></div>
      <button class="secondary" :disabled="saving" @click="open(post)">查看并审核</button>    </div>
    <button v-if="more" class="secondary" :disabled="loading" @click="load(true)">加载更多作品</button>
  </section>
  <section v-if="selected" class="panel review-panel" aria-label="审核作品">
    <div class="section-heading"><h2>{{ selected.title || '未命名作品' }}</h2><button class="quiet" :disabled="saving" @click="selected = null">关闭</button></div>
    <p class="plain-text">{{ selected.content }}</p><PostMedia :clips="selected.clips" />
    <div class="ai-suggest">
      <div class="section-heading">
        <h3>AI 审查建议</h3>
        <button class="secondary" :disabled="suggesting || saving" @click="loadSuggestion">{{ suggesting ? '分析中…' : suggestion ? '重新分析' : '获取 AI 建议' }}</button>
      </div>
      <p v-if="suggestionError" role="alert" class="error">{{ suggestionError }}</p>
      <div v-if="suggestion" class="ai-result">
        <div class="ai-verdict">
          <span :class="['badge', riskClass[suggestion.risk_level]]">{{ riskLabels[suggestion.risk_level] }}</span>
          <span :class="['badge', actionClass[suggestion.recommended_action]]">{{ actionLabels[suggestion.recommended_action] }}</span>
          <span class="muted fine">{{ suggestion.provider }}{{ suggestion.degraded ? '（规则降级）' : '' }} · {{ suggestion.latency_ms }} ms · 轨迹 {{ suggestion.trace_id.slice(0, 8) }}</span>
        </div>
        <p class="plain-text">{{ suggestion.reason }}</p>
        <p v-if="suggestion.rule_hits.length" class="muted fine">规则命中：{{ suggestion.rule_hits.join('、') }}</p>
        <button class="quiet" :disabled="saving" @click="applySuggestion">将理由填入审核理由</button>
      </div>
      <p v-else class="muted fine">仅建议、不自动审核；最终通过/驳回仍由管理员人工决定。</p>
    </div>
    <label>审核理由（必填，最多 500 字）<textarea v-model="reason" maxlength="500" rows="3" :disabled="saving" /></label>
    <div class="actions"><button :disabled="saving || !reason.trim()" @click="decide('PUBLISHED')">通过</button><button class="danger" :disabled="saving || !reason.trim()" @click="decide('REJECTED')">驳回</button></div>
  </section>
  <section class="panel"><h2>审核记录</h2><p v-if="!records.length" class="muted">暂无审核记录</p>
    <div class="table-wrap" v-else><table><thead><tr><th>作品 / 操作人</th><th>状态变化</th><th>理由</th><th>审核时间</th></tr></thead><tbody>
      <tr v-for="record in records" :key="record.id"><td>{{ record.post_id }}<small>{{ record.actor_id }}</small></td><td>{{ labels[record.before_status] }} → {{ labels[record.after_status] }}</td><td class="plain-text">{{ record.reason }}</td><td>{{ date(record.created_at) }}</td></tr>
    </tbody></table></div><p v-if="auditLoading" class="muted" role="status">正在读取审核记录…</p>
  </section>
</template>
<style scoped>
.ai-suggest { margin: 20px 0 6px; padding: 18px; border: 1px solid var(--line); border-radius: var(--radius-m); background: linear-gradient(180deg, #fbfcff, #f7f9fe); }
.ai-suggest .section-heading { margin-bottom: 12px; }
.ai-suggest h3 { color: var(--ink-700); }
.ai-result { display: grid; gap: 10px; }
.ai-verdict { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
</style>
