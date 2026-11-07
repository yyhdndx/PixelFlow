<script setup>
import { computed } from 'vue'
const props = defineProps({ post: { type: Object, required: true } })
const cover = computed(() => (props.post.clips && props.post.clips[0]) || null)
const isVideo = computed(() => props.post.clips?.some(c => c.type === 1))
const author = computed(() => props.post.author || {})
const initial = computed(() => (author.value.nickname || author.value.username || 'U').trim().charAt(0).toUpperCase())
</script>

<template>
  <article class="card">
    <RouterLink :to="`/post/${post.post_id}`" class="post-link">
      <img v-if="cover" :src="cover.url" class="cover" :class="{ video: isVideo }" alt="封面" referrerpolicy="no-referrer">
      <p class="card-title card-body">{{ post.title || '（无标题）' }}</p>
    </RouterLink>
    <div class="card-meta card-body">
      <RouterLink :to="`/author/${author.user_id}`" class="author-link">
        <span class="avatar"><img v-if="author.avatar" :src="author.avatar" alt=""><template v-else>{{ initial }}</template></span>
        <span class="author">{{ author.nickname || author.username }}</span>
      </RouterLink>
      <span class="likes">♥ {{ post.like_count || 0 }}</span>
    </div>
  </article>
</template>
