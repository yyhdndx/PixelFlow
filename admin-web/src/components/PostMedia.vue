<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { request } from '../api/client'
const props = defineProps({ clips: { type: Array, default: () => [] }, compact: Boolean })
const items = ref([])
let revision = 0
function release() { items.value.forEach(item => { if (item.blob) URL.revokeObjectURL(item.url) }); items.value = [] }
watch(() => props.clips, async clips => {
  const current = ++revision
  release()
  for (const clip of (props.compact ? clips.slice(0, 1) : clips)) {
    const source = clip.cover || clip.url
    if (!source) continue
    try {
      const parsed = new URL(source, location.origin)
      const privatePath = /^\/api\/media\/[^/]+\/content$/.test(parsed.pathname)
      let item
      if (privatePath) {
        const blob = await request(parsed.pathname, { blob: true })
        item = { url: URL.createObjectURL(blob), blob: true, video: false }
      } else if (['http:', 'https:'].includes(parsed.protocol)) {
        item = { url: parsed.href, video: clip.type === 1 }
      }
      if (current !== revision) { if (item?.blob) URL.revokeObjectURL(item.url); return }
      if (item) items.value.push(item)
    } catch { if (current === revision) items.value.push({ error: true }) }
  }
}, { immediate: true })
onBeforeUnmount(() => { revision++; release() })
</script>
<template>
  <div :class="['media', { compact }]">
    <template v-for="(item, index) in items" :key="index">
      <span v-if="item.error" class="muted">封面读取失败</span>
      <video v-else-if="item.video" :src="item.url" controls preload="metadata" />
      <img v-else :src="item.url" alt="作品媒体" referrerpolicy="no-referrer">
    </template>
    <span v-if="!items.length" class="muted">暂无封面</span>
  </div>
</template>
