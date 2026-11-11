<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { request, session } from '../api/client'
import { takeCreation } from '../api/creationEntry'

const router = useRouter()
const route = useRoute()
const from = ref(typeof route.query.from === 'string' && route.query.from.startsWith('/') && !route.query.from.startsWith('//') && !route.query.from.startsWith('/me/works/new') ? route.query.from : '/')
const title = ref('')
const content = ref('')
const images = ref([])
const busy = ref(false)
const error = ref('')
let revision = 0
let pending = null

function release() {
  for (const image of images.value) URL.revokeObjectURL(image.preview)
  images.value = []
}
function addFiles(files) {
  error.value = ''
  if (images.value.length + files.length > 9) { error.value = '最多选择 9 张图片'; return }
  if (files.some(file => !['image/jpeg', 'image/png'].includes(file.type) || file.size > 8 * 1024 * 1024)) {
    error.value = '仅支持不超过 8 MB 的 JPEG 或 PNG 图片'
    return
  }
  for (const file of files) images.value.push({ file, preview: URL.createObjectURL(file), id: null })
}
function selected(event) {
  addFiles(Array.from(event.target.files || []))
  event.target.value = ''
}
function acceptCreation() {
  const selection = takeCreation(session.generation)
  if (selection) {
    if (!images.value.length) from.value = selection.from
    addFiles(selection.files)
  }
}
onMounted(() => { acceptCreation(); window.addEventListener('pixelflow:creation-selected', acceptCreation) })
function remove(index) {
  const [image] = images.value.splice(index, 1)
  URL.revokeObjectURL(image.preview)
}
async function save() {
  if (busy.value) return
  const current = revision
  busy.value = true
  error.value = ''
  try {
    for (const image of images.value) {
      if (image.id) continue
      const form = new FormData()
      form.append('file', image.file)
      const uploaded = await request('/api/media', { method: 'POST', body: form })
      if (current !== revision) return
      if (!uploaded.mediaId) throw new Error('图片上传响应不完整')
      image.id = uploaded.mediaId
    }
    const body = JSON.stringify({ title: title.value, content: content.value, mediaIds: images.value.map(image => image.id) })
    if (!pending || pending.body !== body) pending = { body, key: crypto.randomUUID() }
    const post = await request('/api/posts', { method: 'POST', headers: { 'Idempotency-Key': pending.key }, body })
    if (current !== revision) return
    if (!post.post_id) throw new Error('草稿响应不完整，请刷新我的作品确认是否已保存')
    router.replace(`/me/works/${post.post_id}/edit`)
  } catch (e) {
    if (current === revision) error.value = e.message
  } finally {
    if (current === revision) busy.value = false
  }
}
watch(() => session.generation, () => { revision++; title.value = ''; content.value = ''; pending = null; release() })
onBeforeUnmount(() => { window.removeEventListener('pixelflow:creation-selected', acceptCreation); revision++; release() })
</script>

<template>
  <section class="work-editor new-work-page">
    <div class="feed-toolbar"><h1>创作作品</h1><RouterLink :to="from">返回</RouterLink></div>
    <form @submit.prevent="save">
      <label>标题<input v-model="title" maxlength="120"></label>
      <label>正文<textarea v-model="content" maxlength="5000" rows="10"></textarea></label>
      <label>话题<input placeholder="#摄影 #旅行" disabled></label>
      <p class="muted shell-note">话题输入暂未开放，不会随草稿保存。</p>
      <div class="image-picker-row"><span>图片</span><label class="image-picker"><span>添加图片</span><input type="file" accept="image/jpeg,image/png" multiple :disabled="busy" aria-label="添加图片" @change="selected"></label><small>{{ images.length }}/9</small></div>
      <ul v-if="images.length" class="draft-images">
        <li v-for="(image, index) in images" :key="image.preview"><img :src="image.preview" alt="已选图片"><button type="button" class="quiet" :disabled="busy" @click="remove(index)">移除</button></li>
      </ul>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="work-actions"><button type="submit" :disabled="busy">{{ busy ? '保存中…' : '保存草稿' }}</button><button type="button" class="secondary" disabled>提交审核</button></div>
      <p class="muted shell-note">此处暂不能提交审核；保存草稿后，可在“我的作品”中提交。</p>
    </form>
  </section>
</template>
