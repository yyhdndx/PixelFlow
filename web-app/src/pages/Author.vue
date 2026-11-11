<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { request } from '../api/client'
import { usePage } from '../api/page'
import PostList from '../components/PostList.vue'
const route = useRoute()
const profile = ref(null)
const error = ref('')
const loading = ref(false)
let revision = 0
const page = usePage(() => `/api/users/${encodeURIComponent(route.params.id)}/posts?count=20`)
async function load() {
  const current = ++revision
  loading.value = true; error.value = ''
  try {
    const result = await request(`/api/users/${encodeURIComponent(route.params.id)}/profile`)
    if (current !== revision) return
    profile.value = result
    page.reset(); page.load()
  } catch (e) { if (current === revision) error.value = e.message }
  finally { if (current === revision) loading.value = false }
}
onMounted(load)
onBeforeUnmount(() => revision++)
</script>

<template>
  <section>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="quiet" @click="load">重试</button></p>
    <p v-else-if="loading" class="spinner">加载中…</p>
    <template v-else-if="profile">
      <div class="profile-head"><span class="profile-avatar"><img v-if="profile.avatar" :src="profile.avatar" alt="头像"><template v-else>{{ (profile.nickname || 'U').charAt(0) }}</template></span><div><h1>{{ profile.nickname }}</h1><p class="profile-bio">{{ profile.bio || '这位作者还没有填写简介。' }}</p><RouterLink to="/me/messages">私信（暂未开放）</RouterLink></div></div>
      <h2>公开作品</h2><PostList :page="page" empty="这位作者暂时没有公开作品。" />
    </template>
  </section>
</template>
