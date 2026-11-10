<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { request, session } from '../api/client'

const route = useRoute()
const title = ref('')
const content = ref('')
const original = ref(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')
let revision = 0
const editable = computed(() => ['DRAFT', 'REJECTED', 'WITHDRAWN'].includes(original.value?.status))

async function load() {
  const current = ++revision
  loading.value = true
  original.value = null
  notice.value = ''
  error.value = ''
  try {
    const post = await request(`/api/posts/${encodeURIComponent(route.params.id)}`)
    if (current !== revision) return
    if (!['DRAFT', 'REJECTED', 'WITHDRAWN'].includes(post.status)) {
      error.value = '当前作品不可编辑'
      return
    }
    original.value = post
    title.value = post.title || ''
    content.value = post.content || ''
  } catch (e) {
    if (current === revision) error.value = e.message
  } finally {
    if (current === revision) loading.value = false
  }
}

async function save() {
  if (saving.value || !editable.value) return
  const current = revision
  saving.value = true
  error.value = ''
  notice.value = ''
  const post = original.value
  const body = { title: title.value, content: content.value }
  if (post.media_ids?.length) body.mediaIds = post.media_ids
  else body.clips = post.clips || []
  try {
    const updated = await request(`/api/posts/${encodeURIComponent(post.post_id)}`, {
      method: 'PUT', body: JSON.stringify(body),
    })
    if (current !== revision) return
    original.value = updated
    notice.value = '草稿已保存'
  } catch (e) {
    if (current === revision) error.value = e.message
  } finally {
    if (current === revision) saving.value = false
  }
}

watch(() => session.generation, () => { revision++; original.value = null; title.value = ''; content.value = ''; notice.value = '' })
onMounted(load)
onBeforeUnmount(() => revision++)
</script>

<template>
  <section class="work-editor">
    <div class="feed-toolbar"><h1>编辑草稿</h1><RouterLink to="/me/works">返回我的作品</RouterLink></div>
    <p v-if="loading" class="spinner">加载中…</p>
    <template v-else-if="editable">
      <p v-if="original.media_ids?.length || original.clips?.length" class="muted">已关联 {{ original.clips?.length || original.media_ids.length }} 项媒体，保存文字时会保留原有媒体。</p>
      <form @submit.prevent="save">
        <label>标题<input v-model="title" maxlength="120"></label>
        <label>正文<textarea v-model="content" maxlength="5000" rows="10"></textarea></label>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <p v-if="notice" class="success" role="status">{{ notice }}</p>
        <button type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存草稿' }}</button>
      </form>
    </template>
    <p v-else-if="error" class="error" role="alert">{{ error }} <button class="quiet" @click="load">重试</button></p>
  </section>
</template>
