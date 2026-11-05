<script setup>
import { onMounted } from 'vue'
import { usePage } from '../api/page'
import PostList from '../components/PostList.vue'
const page = usePage(() => '/api/users/me/favorites?count=20')
onMounted(() => page.load())
</script>

<template><section><h1>我的收藏</h1><PostList :page="page" empty="还没有收藏，去社区发现喜欢的作品吧。" /></section></template>
