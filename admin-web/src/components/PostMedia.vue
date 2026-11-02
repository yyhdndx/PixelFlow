<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { request } from '../api/client'
const props = defineProps({ clips: { type: Array, default: () => [] }, compact: Boolean })
const items = ref([])
const index = ref(0)
let revision = 0
function release() { items.value.forEach(item => { if (item.blob) URL.revokeObjectURL(item.url) }); items.value = [] }
watch(() => props.clips, async clips => {
  const current = ++revision
  release()
  index.value = 0
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
function prev() { index.value = (index.value - 1 + items.value.length) % items.value.length }
function next() { index.value = (index.value + 1) % items.value.length }
onBeforeUnmount(() => { revision++; release() })
</script>
<template>
  <div v-if="compact" class="media compact">
    <template v-for="(item, i) in items" :key="i">
      <span v-if="item.error" class="muted">封面读取失败</span>
      <video v-else-if="item.video" :src="item.url" controls preload="metadata" />
      <img v-else :src="item.url" alt="作品媒体" referrerpolicy="no-referrer">
    </template>
    <span v-if="!items.length" class="muted">暂无封面</span>
  </div>
  <div v-else class="carousel" role="group" aria-label="作品媒体轮播">
    <template v-if="items.length">
      <div class="stage">
        <button v-if="items.length > 1" class="arrow left" type="button" aria-label="上一个"
                :disabled="items[index]?.error" @click="prev">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
               stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <polyline points="15 18 9 12 15 6" />
          </svg>
        </button>
        <span v-if="items[index]?.error" class="muted">媒体读取失败</span>
        <video v-else-if="items[index]?.video" :src="items[index].url" controls preload="metadata" />
        <img v-else :src="items[index].url" alt="作品媒体" referrerpolicy="no-referrer">
        <button v-if="items.length > 1" class="arrow right" type="button" aria-label="下一个"
                :disabled="items[index]?.error" @click="next">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
               stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <polyline points="9 18 15 12 9 6" />
          </svg>
        </button>
      </div>
      <div class="bar">
        <span class="muted fine">{{ index + 1 }} / {{ items.length }}</span>
        <span v-if="items.length > 1" class="dots">
          <button v-for="(item, i) in items" :key="i" type="button"
                  :class="['dot', { on: i === index }]" :aria-label="`第 ${i + 1} 个媒体`"
                  @click="index = i" />
        </span>
      </div>
    </template>
    <span v-else class="muted">暂无封面</span>
  </div>
</template>
<style scoped>
.carousel { display: grid; gap: 8px; }
.stage { position: relative; display: flex; align-items: center; justify-content: center; }
.stage img, .stage video { max-width: 100%; max-height: 420px; border-radius: var(--radius-m, 10px); display: block; }
.arrow {
  position: absolute; top: 50%; transform: translateY(-50%);
  width: 36px; height: 36px; padding: 0;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 50%; border: 1px solid var(--line, #e3e6ee);
  background: rgba(255, 255, 255, .92); color: var(--ink-700, #2c3245);
  cursor: pointer; z-index: 1;
  box-shadow: 0 2px 8px rgba(23, 28, 45, .12);
  transition: background .15s ease;
}
/* 全局 button:hover/:active 会覆盖 transform，必须在这里顶住，否则悬停时箭头跳位 */
.carousel .arrow:hover:not(:disabled) {
  transform: translateY(-50%); filter: none;
  background: #fff; box-shadow: 0 2px 8px rgba(23, 28, 45, .12);
}
.carousel .arrow:active:not(:disabled) { transform: translateY(-50%); filter: none; }
.arrow.left { left: 10px; }
.arrow.right { right: 10px; }
.bar { display: flex; align-items: center; justify-content: center; gap: 12px; min-height: 18px; }
.dots { display: inline-flex; gap: 6px; }
.dot {
  width: 8px; height: 8px; padding: 0; border-radius: 50%;
  border: none; background: var(--line, #e3e6ee); cursor: pointer;
  transition: background .15s ease, transform .15s ease;
}
.carousel .dot:hover:not(:disabled) { transform: none; filter: none; box-shadow: none; }
.dot.on { background: var(--accent, #5a6cf3); transform: scale(1.15); }
.carousel .dot.on:hover:not(:disabled) { transform: scale(1.15); }
</style>
