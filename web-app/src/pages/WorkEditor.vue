<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { request, session } from '../api/client'
import { assists, draftKey, generateAssist, cancelAssist, discardAssist } from '../api/draftAssist'

const route = useRoute()
const title = ref('')
const content = ref('')
const original = ref(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const images = ref([])
const assistGoal = ref('all')
const assistPrompt = ref('')
const assist = computed(() => original.value ? assists.get(draftKey(original.value.post_id)) : null)
const assistChanged = computed(() => assist.value && (assist.value.snapshot.title !== title.value || assist.value.snapshot.content !== content.value ||
  assist.value.snapshot.version !== original.value?.content_version ||
  assist.value.goal !== assistGoal.value || assist.value.prompt !== assistPrompt.value.trim()))
const assistCandidates = computed(() => assist.value?.status === 'done' && assist.value.result &&
  ((assist.value.goal === 'suggest_tags' && assist.value.result.tag_suggestions?.length) ||
   (assist.value.goal !== 'suggest_tags' && (assist.value.result.title_candidates.length || assist.value.result.content_candidates.length))))
function generate() {
  if (!original.value || !title.value.trim() && !content.value.trim() && !assistPrompt.value.trim()) {
    error.value = '请先输入草稿内容或写作要求'
    return
  }
  error.value = ''
  generateAssist(original.value.post_id, { title: title.value, content: content.value, version: original.value.content_version }, assistGoal.value, assistPrompt.value)
}
function adopt() {
  if (!assistCandidates.value || !assist.value || assistChanged.value || assist.value.status !== 'done') return
  const result = assist.value.result
  if (result.title_candidates.length) title.value = result.title_candidates[0]
  if (result.content_candidates.length) content.value = result.content_candidates[0]
  discardAssist(original.value.post_id)
  notice.value = '候选已填入编辑器，请检查后保存草稿'
}
let revision = 0

function dispose(image) {
  if (!image.clip && image.preview) URL.revokeObjectURL(image.preview)
}
function release() {
  for (const image of images.value) dispose(image)
  images.value = []
}
async function preview(image, current) {
  try {
    const blob = await request(`/api/media/${encodeURIComponent(image.id)}/content`, {}, true, 'blob')
    if (current !== revision || !images.value.includes(image)) return
    image.preview = URL.createObjectURL(blob)
  } catch (e) {
    if (current === revision) image.error = e.message
  }
}
function selected(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  error.value = ''
  notice.value = ''
  if (images.value.length + files.length > 9) { error.value = '最多选择 9 张图片'; return }
  if (files.length && images.value.some(image => image.clip)) { error.value = '请先移除原有外部媒体，再添加上传图片'; return }
  if (files.some(file => !['image/jpeg', 'image/png'].includes(file.type) || file.size > 8 * 1024 * 1024)) {
    error.value = '仅支持不超过 8 MB 的 JPEG 或 PNG 图片'
    return
  }
  for (const file of files) images.value.push({ file, preview: URL.createObjectURL(file), id: null })
}
function remove(index) {
  const [image] = images.value.splice(index, 1)
  dispose(image)
  notice.value = ''
}
function move(index, direction) {
  const target = index + direction
  if (target < 0 || target >= images.value.length) return
  const [image] = images.value.splice(index, 1)
  images.value.splice(target, 0, image)
  notice.value = ''
}
const editable = computed(() => ['DRAFT', 'REJECTED', 'WITHDRAWN'].includes(original.value?.status))

async function load() {
  const current = ++revision
  loading.value = true
  saving.value = false
  original.value = null
  release()
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
    const restored = assists.get(draftKey(post.post_id))
    assistGoal.value = restored?.goal || 'all'
    assistPrompt.value = restored?.prompt || ''
    if (post.media_ids?.length) {
      images.value = post.media_ids.map(id => ({ id, preview: '', error: '' }))
      for (const image of images.value) preview(image, current)
    } else {
      images.value = (post.clips || []).map(clip => ({ clip, preview: clip.url }))
    }
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
  const selectedImages = [...images.value]
  const savedTitle = title.value
  const savedContent = content.value
  try {
    for (const image of selectedImages) {
      if (!image.file || image.id) continue
      const form = new FormData()
      form.append('file', image.file)
      const uploaded = await request('/api/media', { method: 'POST', body: form })
      if (current !== revision) return
      if (!uploaded.mediaId) throw new Error('图片上传响应不完整')
      image.id = uploaded.mediaId
    }
    const body = { title: savedTitle, content: savedContent }
    if (selectedImages.some(image => !image.clip)) body.mediaIds = selectedImages.map(image => image.id)
    else body.clips = selectedImages.map(image => image.clip)
    const updated = await request(`/api/posts/${encodeURIComponent(post.post_id)}`, {
      method: 'PUT', body: JSON.stringify(body),
    })
    if (current !== revision) return
    original.value = updated
    const unchanged = title.value === savedTitle && content.value === savedContent &&
      images.value.length === selectedImages.length && images.value.every((image, index) => image === selectedImages[index])
    notice.value = unchanged ? '草稿已保存' : '之前的修改已保存，当前仍有未保存的改动'
  } catch (e) {
    if (current === revision) error.value = e.message
  } finally {
    if (current === revision) saving.value = false
  }
}

watch(() => route.params.id, load)
watch(() => session.generation, () => { revision++; release(); original.value = null; title.value = ''; content.value = ''; notice.value = ''; saving.value = false })
onMounted(load)
onBeforeUnmount(() => { revision++; release() })
</script>

<template>
  <section class="work-editor">
    <div class="feed-toolbar"><h1>编辑草稿</h1><div class="work-actions"><RouterLink :to="`/assistant/create/${route.params.id}`">AI 助手</RouterLink><RouterLink to="/me/works">返回我的作品</RouterLink></div></div>
    <p v-if="loading" class="spinner">加载中…</p>
    <template v-else-if="editable">
      <form @submit.prevent="save">
        <label>标题<input v-model="title" maxlength="120"></label>
        <label>正文<textarea v-model="content" maxlength="5000" rows="10"></textarea></label>
        <label>话题<input placeholder="#摄影 #旅行" disabled></label>
        <p class="muted shell-note">话题输入暂未开放，不会随草稿保存。</p>
        <div class="image-picker-row"><span>图片</span><label class="image-picker"><span>添加图片</span><input type="file" accept="image/jpeg,image/png" multiple :disabled="saving" aria-label="添加图片" @change="selected"></label><small>{{ images.length }}/9</small></div>
        <ul v-if="images.length" class="draft-images">
          <li v-for="(image, index) in images" :key="image.id || image.preview">
            <img v-if="image.preview" :src="image.preview" alt="草稿图片">
            <span v-else class="muted">{{ image.error || '正在读取图片…' }}</span>
            <div class="draft-image-actions">
              <button type="button" class="quiet" :disabled="saving || index === 0" @click="move(index, -1)">前移</button>
              <button type="button" class="quiet" :disabled="saving || index === images.length - 1" @click="move(index, 1)">后移</button>
              <button type="button" class="quiet" :disabled="saving" @click="remove(index)">移除</button>
            </div>
          </li>
        </ul>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <p v-if="notice" class="success" role="status">{{ notice }}</p>
        <div class="work-actions"><button type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存草稿' }}</button><button type="button" class="secondary" disabled>提交审核</button></div>
        <p class="muted shell-note">此处暂不能提交审核；保存草稿后，可在“我的作品”中提交。</p>
      </form>
      <section class="draft-assist">
        <h2>文案建议</h2>
        <label>生成目标<select v-model="assistGoal"><option value="all">综合建议</option><option value="improve_title">优化标题</option><option value="polish_content">润色正文</option><option value="write_content">续写正文</option><option value="suggest_tags">推荐话题</option></select></label>
        <label>写作要求<input v-model="assistPrompt" maxlength="200" placeholder="可填写需要调整的方向"></label>
        <div class="work-actions"><button type="button" class="secondary" :disabled="assist?.status === 'waiting'" @click="generate">生成建议</button><button v-if="assist?.status === 'waiting'" class="secondary" @click="cancelAssist(original.post_id)">停止</button><button v-if="assist" class="quiet" @click="discardAssist(original.post_id)">放弃建议</button></div>
        <template v-if="assist">
          <p role="status">{{ { waiting: '等待生成', done: '建议已完成', cancelled: '已停止', failed: '建议生成失败' }[assist.status] }}</p>
          <p v-if="assist.error" class="error" role="alert">{{ assist.error }}</p>
          <p v-if="assistChanged" class="muted">编辑器内容已变化，此建议对应旧输入。请重新生成后再采纳。</p>
          <template v-if="assist.status === 'done'">
            <p v-if="assist.result.title_candidates.length"><strong>标题预览：</strong>{{ assist.result.title_candidates[0] }}</p>
            <p v-if="assist.result.content_candidates.length" class="agent-answer-text"><strong>正文预览：</strong>{{ assist.result.content_candidates[0] }}</p>
            <p v-if="assist.result.tag_suggestions?.length"><strong>话题建议：</strong>{{ assist.result.tag_suggestions.join('、') }}</p>
            <button v-if="assistCandidates && assist.goal !== 'suggest_tags'" class="secondary" :disabled="assistChanged" @click="adopt">采纳到编辑器</button>
          </template>
        </template>
      </section>
    </template>
    <p v-else-if="error" class="error" role="alert">{{ error }} <button class="quiet" @click="load">重试</button></p>
  </section>
</template>
