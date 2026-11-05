<script setup>
import { computed } from 'vue'
const props = defineProps({ post: { type: Object, required: true } })
const cover = computed(() => (props.post.clips && props.post.clips[0]) || null)
const isVideo = computed(() => props.post.clips?.some(c => c.type === 1))
const author = computed(() => props.post.author || {})
const initial = computed(() => (author.value.nickname || author.value.username || 'U').trim().charAt(0).toUpperCase())
</script>

<template>
  <RouterLink :to="`/post/${post.post_id}`" class="card">
    <img v-if="cover" :src="cover.url" class="cover" :class="{ video: isVideo }" alt="封面" referrerpolicy="no-referrer">
    <div class="card-body">
      <p class="card-title">{{ post.title || '（无标题）' }}</p>
      <div class="card-meta">
        <span class="avatar"><img v-if="author.avatar" :src="author.avatar" alt=""><template v-else>{{ initial }}</template></span>
        <span class="author">{{ author.nickname || author.username }}</span>
        <span class="likes">♥ {{ post.like_count || 0 }}</span>
      </div>
    </div>
  </RouterLink>
</template>
