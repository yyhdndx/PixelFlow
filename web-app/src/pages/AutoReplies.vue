<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { request, session } from '../api/client'
import { ruleSuggestions, ruleKey, suggestRules, stopRuleSuggestion, discardRuleSuggestion } from '../api/ruleSuggestions'

const route = useRoute()
const post = ref(null)
const rules = ref([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const question = ref('')
const answer = ref('')
const description = ref('')
const suggestion = computed(() => post.value ? ruleSuggestions.get(ruleKey(post.value)) : null)
const suggestionChanged = computed(() => suggestion.value && suggestion.value.prompt !== description.value.trim())
const selectedRules = computed(() => suggestion.value?.items.filter(item => item.selected) || [])
function generate() {
  if (!description.value.trim()) { error.value = '请先描述希望自动回复的问题'; return }
  error.value = ''; notice.value = ''
  suggestRules(post.value, description.value)
}
async function applySuggestions() {
  if (busy.value || suggestion.value?.status !== 'done' || suggestionChanged.value || !selectedRules.value.length ||
      selectedRules.value.length + rules.value.length > 10 || !selectedRules.value.every(item => valid(item.question, item.answer))) return
  const current = revision
  busy.value = true; error.value = ''; notice.value = ''
  const chosen = selectedRules.value.map(item => ({ question: item.question.trim(), answer: item.answer.trim() }))
  try {
    const saved = await request(`/api/posts/${encodeURIComponent(post.value.post_id)}/auto-replies/batch`, {
      method: 'POST', body: JSON.stringify({ rules: chosen }),
    })
    if (current !== revision) return
    if (!Array.isArray(saved.created) || saved.created.length !== chosen.length) throw new Error('保存响应不完整，请刷新确认')
    rules.value.push(...saved.created.map(editable))
    discardRuleSuggestion(post.value)
    notice.value = '已保存选中的规则'
  } catch (e) { if (current === revision) error.value = e.message }
  finally { if (current === revision) busy.value = false }
}
let revision = 0
const editable = rule => ({ ...rule, draftQuestion: rule.question, draftAnswer: rule.answer, draftEnabled: rule.enabled })
async function load() {
  const current = ++revision
  loading.value = true; error.value = ''; notice.value = ''
  try {
    const id = encodeURIComponent(route.params.id)
    const [work, result] = await Promise.all([request(`/api/posts/${id}`), request(`/api/posts/${id}/auto-replies`)])
    if (current !== revision) return
    if (work.author?.user_id !== session.user?.id) throw new Error('只有作者可以管理回复规则')
    if (!Array.isArray(result.rules)) throw new Error('回复规则格式无效')
    post.value = work
    rules.value = result.rules.map(editable)
  } catch (e) { if (current === revision) error.value = e.message }
  finally { if (current === revision) loading.value = false }
}
function valid(q, a) { return q.trim().length > 0 && q.trim().length <= 200 && a.trim().length > 0 && a.trim().length <= 300 }
async function add() {
  if (busy.value || !valid(question.value, answer.value)) return
  const current = revision
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const saved = await request(`/api/posts/${encodeURIComponent(route.params.id)}/auto-replies`, {
      method: 'POST', body: JSON.stringify({ question: question.value.trim(), answer: answer.value.trim() }),
    })
    if (current !== revision) return
    rules.value.push(editable(saved)); question.value = ''; answer.value = ''; notice.value = '规则已保存'
  } catch (e) { if (current === revision) error.value = e.message }
  finally { if (current === revision) busy.value = false }
}
async function update(rule) {
  if (busy.value || !valid(rule.draftQuestion, rule.draftAnswer)) return
  const current = revision
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const saved = await request(`/api/posts/${encodeURIComponent(route.params.id)}/auto-replies/${encodeURIComponent(rule.id)}`, {
      method: 'PUT', body: JSON.stringify({ question: rule.draftQuestion.trim(), answer: rule.draftAnswer.trim(), enabled: rule.draftEnabled }),
    })
    if (current !== revision) return
    Object.assign(rule, editable(saved)); notice.value = '规则已更新'
  } catch (e) { if (current === revision) error.value = e.message }
  finally { if (current === revision) busy.value = false }
}
async function remove(rule) {
  if (busy.value || !window.confirm('确认删除这条回复规则？')) return
  const current = revision
  busy.value = true; error.value = ''; notice.value = ''
  try {
    await request(`/api/posts/${encodeURIComponent(route.params.id)}/auto-replies/${encodeURIComponent(rule.id)}`, { method: 'DELETE' })
    if (current !== revision) return
    rules.value = rules.value.filter(item => item.id !== rule.id); notice.value = '规则已删除'
  } catch (e) { if (current === revision) error.value = e.message }
  finally { if (current === revision) busy.value = false }
}
watch(() => route.params.id, load)
watch(() => session.generation, () => { revision++; post.value = null; rules.value = []; loading.value = false; busy.value = false })
onMounted(load)
onBeforeUnmount(() => revision++)
</script>

<template>
  <section class="work-editor">
    <div class="feed-toolbar"><h1>回复规则</h1><RouterLink to="/me/works">返回我的作品</RouterLink></div>
    <p v-if="loading" class="spinner">加载中…</p>
    <p v-if="error" class="error" role="alert">{{ error }} <button v-if="!post" class="quiet" @click="load">重试</button></p>
    <p v-if="notice" class="success" role="status">{{ notice }}</p>
    <template v-if="post">
      <h2>{{ post.title || '（无标题）' }}</h2>
      <p v-if="!rules.length">还没有回复规则。</p>
      <ul class="preference-list"><li v-for="rule in rules" :key="rule.id" class="reply-rule">
        <label>适用场景<input v-model="rule.draftQuestion" maxlength="200"></label>
        <label>回复内容<textarea v-model="rule.draftAnswer" maxlength="300" rows="3"></textarea></label>
        <label class="interest-toggle"><input v-model="rule.draftEnabled" type="checkbox">启用规则</label>
        <div class="work-actions"><button class="secondary" :disabled="busy || !valid(rule.draftQuestion, rule.draftAnswer)" @click="update(rule)">保存修改</button><button class="danger" :disabled="busy" @click="remove(rule)">删除</button></div>
      </li></ul>
      <section v-if="rules.length < 10" class="draft-assist">
        <h2>提炼回复建议</h2>
        <label>描述常见问题和希望的回复<textarea v-model="description" maxlength="500" rows="3"></textarea></label>
        <div class="work-actions"><button class="secondary" :disabled="suggestion?.status === 'waiting'" @click="generate">生成建议</button><button v-if="suggestion?.status === 'waiting'" class="secondary" @click="stopRuleSuggestion">停止</button><button v-if="suggestion" class="quiet" @click="discardRuleSuggestion(post)">放弃建议</button></div>
        <template v-if="suggestion">
          <p role="status">{{ { waiting: '等待生成', done: '建议已完成', cancelled: '已停止', failed: '建议生成失败' }[suggestion.status] }}</p>
          <p v-if="suggestion.error" class="error" role="alert">{{ suggestion.error }}</p>
          <p v-if="suggestionChanged" class="muted">描述已变化，请重新生成后再保存建议。</p>
          <ul v-if="suggestion.status === 'done'" class="preference-list"><li v-for="(item, index) in suggestion.items" :key="index" class="reply-rule">
            <label class="interest-toggle"><input v-model="item.selected" type="checkbox">选用这条</label>
            <label>适用场景<input v-model="item.question" maxlength="200"></label>
            <label>回复内容<textarea v-model="item.answer" maxlength="300" rows="3"></textarea></label>
          </li></ul>
          <button v-if="suggestion.status === 'done'" class="secondary" :disabled="busy || suggestionChanged || !selectedRules.length || selectedRules.length + rules.length > 10 || !selectedRules.every(item => valid(item.question, item.answer))" @click="applySuggestions">保存选中的规则</button>
        </template>
      </section>
      <form v-if="rules.length < 10" @submit.prevent="add">
        <h2>添加规则</h2>
        <label>适用场景<input v-model="question" maxlength="200"></label>
        <label>回复内容<textarea v-model="answer" maxlength="300" rows="3"></textarea></label>
        <button type="submit" :disabled="busy || !valid(question, answer)">保存规则</button>
      </form>
    </template>
  </section>
</template>
