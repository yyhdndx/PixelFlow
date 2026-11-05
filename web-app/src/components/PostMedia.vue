<script setup>
import { computed, ref } from 'vue'
const props = defineProps({ clips: { type: Array, default: () => [] } })
const index = ref(0)
const items = computed(() => props.clips.filter(c => c && c.url))
function isVideo(c) { return c.type === 1 }
function prev() { index.value = (index.value - 1 + items.value.length) % items.value.length }
function next() { index.value = (index.value + 1) % items.value.length }
</script>

<template>
  <div class="media-carousel" role="group" aria-label="作品媒体">
    <template v-if="items.length">
      <div class="stage">
        <video v-if="isVideo(items[index])" :src="items[index].url" controls preload="metadata" />
        <img v-else :src="items[index].url" alt="作品媒体" referrerpolicy="no-referrer">
        <button v-if="items.length > 1" class="arrow left" type="button" aria-label="上一个" @click="prev">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
        </button>
        <button v-if="items.length > 1" class="arrow right" type="button" aria-label="下一个" @click="next">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
        </button>
      </div>
      <div v-if="items.length > 1" class="dots">
        <button v-for="(_, i) in items" :key="i" type="button" :class="['dot', { on: i === index }]" :aria-label="`第 ${i + 1} 个媒体`" @click="index = i" />
      </div>
    </template>
    <div v-else class="empty-media">暂无媒体</div>
  </div>
</template>

<style scoped>
.media-carousel { display: grid; gap: 10px; }
.stage { position: relative; display: flex; align-items: center; justify-content: center; }
.stage img, .stage video { max-width: 100%; max-height: 66vh; border-radius: 8px; display: block; background: #141824; }
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
</style>
