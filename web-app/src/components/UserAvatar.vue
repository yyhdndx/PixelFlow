<script setup>
import { computed } from 'vue'
const props = defineProps({
  user: { type: Object, default: null },
  size: { type: Number, default: 32 },
  label: { type: String, default: '' },
})
const initial = computed(() => {
  const name = props.user?.nickname || props.user?.username || props.label || 'U'
  return String(name).charAt(0).toUpperCase()
})
const src = computed(() => props.user?.avatar || '')
const style = computed(() => ({
  width: props.size + 'px',
  height: props.size + 'px',
  fontSize: Math.round(props.size * 0.42) + 'px',
}))
</script>

<template>
  <span class="user-avatar" :style="style" :aria-label="user?.nickname || label || '用户头像'">
    <img v-if="src" :src="src" alt="" referrerpolicy="no-referrer">
    <template v-else>{{ initial }}</template>
  </span>
</template>

<style scoped>
.user-avatar {
  display: inline-grid; place-items: center; flex-shrink: 0; overflow: hidden;
  border-radius: 50%; background: linear-gradient(135deg, var(--brand-500), #7a5af8);
  color: #fff; font-weight: 700; line-height: 1;
}
.user-avatar img { width: 100%; height: 100%; object-fit: cover; display: block; }
</style>
