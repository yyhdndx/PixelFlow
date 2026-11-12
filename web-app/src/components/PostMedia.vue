<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
const props = defineProps({ clips: { type: Array, default: () => [] } })
const index = ref(0)
const items = computed(() => props.clips.filter(c => c && c.url))
const zoom = ref(false)
function isVideo(c) { return c.type === 1 }
function prev() { index.value = (index.value - 1 + items.value.length) % items.value.length }
function next() { index.value = (index.value + 1) % items.value.length }
function openZoom() {
  if (!items.value.length || isVideo(items.value[index.value])) return
  zoom.value = true
  document.body.style.overflow = 'hidden'
}
function closeZoom() {
  zoom.value = false
  document.body.style.overflow = ''
}
function onKey(e) {
  if (!zoom.value) return
  if (e.key === 'Escape') closeZoom()
  else if (e.key === 'ArrowLeft') prev()
  else if (e.key === 'ArrowRight') next()
}
watch(zoom, (v) => { if (!v) document.body.style.overflow = '' })
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  document.body.style.overflow = ''
})
</script>

<template>
  <div class="media-carousel" role="group" aria-label="作品媒体">
    <template v-if="items.length">
      <div class="stage">
        <video v-if="isVideo(items[index])" :src="items[index].url" controls preload="metadata" />
        <img v-else :src="items[index].url" alt="作品媒体" referrerpolicy="no-referrer"
             class="zoomable" title="点击查看大图" @click="openZoom">
        <button v-if="items.length > 1" class="arrow left" type="button" aria-label="上一个" @click.stop="prev">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
        </button>
        <button v-if="items.length > 1" class="arrow right" type="button" aria-label="下一个" @click.stop="next">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
        </button>
      </div>
      <div v-if="items.length > 1" class="dots">
        <button v-for="(_, i) in items" :key="i" type="button" :class="['dot', { on: i === index }]" :aria-label="`第 ${i + 1} 个媒体`" @click="index = i" />
      </div>
      <p class="zoom-hint">点击图片查看大图</p>
    </template>
    <div v-else class="empty-media">暂无媒体</div>

    <Teleport to="body">
      <div v-if="zoom" class="lightbox" role="dialog" aria-modal="true" aria-label="查看大图" @click.self="closeZoom">
        <button class="lightbox-close" type="button" aria-label="关闭" @click="closeZoom">×</button>
        <button v-if="items.length > 1" class="lightbox-arrow left" type="button" aria-label="上一个" @click.stop="prev">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
        </button>
        <img :src="items[index].url" alt="作品大图" referrerpolicy="no-referrer" @click.stop>
        <button v-if="items.length > 1" class="lightbox-arrow right" type="button" aria-label="下一个" @click.stop="next">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
        </button>
        <p v-if="items.length > 1" class="lightbox-count">{{ index + 1 }} / {{ items.length }}</p>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.media-carousel { display: grid; gap: 10px; }
.stage { position: relative; display: flex; align-items: center; justify-content: center; }
.stage img, .stage video { max-width: 100%; max-height: 66vh; border-radius: 8px; display: block; background: #141824; }
.stage img.zoomable { cursor: zoom-in; }
.arrow {
  position: absolute; top: 50%; transform: translateY(-50%); width: 34px; height: 34px; padding: 0;
  border-radius: 50%; background: rgba(20,24,36,.55); color: #fff; display: inline-flex;
  align-items: center; justify-content: center; cursor: pointer; border: none; z-index: 1;
}
.arrow:hover:not(:disabled) { background: rgba(20,24,36,.8); filter: none; transform: translateY(-50%); }
.arrow.left { left: 10px; } .arrow.right { right: 10px; }
.dots { display: flex; justify-content: center; gap: 6px; }
.dot { width: 8px; height: 8px; padding: 0; border-radius: 50%; background: var(--line); border: none; }
.dot.on { background: var(--brand-500); transform: scale(1.15); }
.empty-media { color: var(--ink-400); padding: 40px; text-align: center; }
.zoom-hint { margin: 0; text-align: center; font-size: 12px; color: var(--ink-400); }

.lightbox {
  position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center;
  background: rgba(9, 12, 20, .92); padding: 32px;
}
.lightbox img { max-width: 92vw; max-height: 88vh; border-radius: 8px; box-shadow: 0 24px 60px rgba(0,0,0,.5); cursor: default; }
.lightbox-close {
  position: absolute; top: 16px; right: 20px; width: 40px; height: 40px; padding: 0;
  background: rgba(255,255,255,.12); color: #fff; border: none; border-radius: 50%;
  font-size: 24px; line-height: 1; cursor: pointer; box-shadow: none;
}
.lightbox-close:hover { background: rgba(255,255,255,.24); filter: none; }
.lightbox-arrow {
  position: absolute; top: 50%; transform: translateY(-50%); width: 46px; height: 46px; padding: 0;
  border-radius: 50%; background: rgba(255,255,255,.14); color: #fff; border: none;
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer; box-shadow: none;
}
.lightbox-arrow:hover { background: rgba(255,255,255,.26); filter: none; }
.lightbox-arrow.left { left: 22px; } .lightbox-arrow.right { right: 22px; }
.lightbox-count { position: absolute; bottom: 18px; left: 50%; transform: translateX(-50%); margin: 0; color: rgba(255,255,255,.75); font-size: 13px; }
</style>
