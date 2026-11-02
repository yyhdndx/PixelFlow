<script setup>
import { ref, onMounted } from 'vue'
import { request, write, session } from '../../api/client'
const users = ref([]), selected = ref(null), reason = ref(''), error = ref(''), notice = ref('')
const loading = ref(false), saving = ref(false), more = ref(false)
const roleLabel = value => ({ ADMIN: '管理员', CREATOR: '创作者', USER: '普通用户' })[value] || value
async function load(append = false) {
  loading.value = true; error.value = ''
  try {
    const after = append && users.value.length ? users.value.at(-1).id : ''
    const rows = await request(`/api/admin/users?count=50&after=${encodeURIComponent(after)}`)
    users.value = append ? [...users.value, ...rows] : rows; more.value = rows.length === 50
  } catch (e) { error.value = e.message } finally { loading.value = false }
}
function open(user) { selected.value = user; reason.value = ''; notice.value = ''; error.value = '' }
async function submit() {
  if (saving.value || !reason.value.trim() || reason.value.length > 500) return
  saving.value = true; error.value = ''; notice.value = ''
  try {
    const result = await write(`/api/admin/users/${selected.value.id}/status`, 'PUT', { enabled: !selected.value.enabled, reason: reason.value })
    const user = users.value.find(user => user.id === result.id)
    if (user) user.enabled = result.enabled
    selected.value = null
    notice.value = result.enabled ? '账号已启用；用户需重新登录' : '账号已禁用，已有会话已撤销'
  } catch (e) { error.value = e.message } finally { saving.value = false }
}
onMounted(() => load())
</script>
<template>
  <header class="page-header"><div><div class="eyebrow">ACCOUNT MANAGEMENT</div><h1>用户管理</h1><p class="muted">账号启停复用服务端权限校验与状态审计。</p></div><button class="secondary" :disabled="loading || saving" @click="load()">刷新</button></header>
  <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="success" role="status">{{ notice }}</p>
  <section class="panel"><p v-if="loading">正在加载…</p><p v-if="!loading && !users.length" class="empty">暂无用户</p>
    <div v-if="users.length" class="table-wrap"><table><thead><tr><th>账号</th><th>昵称</th><th>角色</th><th>状态</th><th>操作</th></tr></thead><tbody>
      <tr v-for="user in users" :key="user.id"><td>{{ user.username }}<small>{{ user.id }}</small></td><td>{{ user.nickname }}</td><td><span :class="['badge', 'neutral']">{{ roleLabel(user.role) }}</span></td><td><span :class="['badge', { disabled: !user.enabled }]">{{ user.enabled ? '已启用' : '已禁用' }}</span></td><td><button class="secondary" :disabled="saving || user.id === session.user?.id" @click="open(user)">{{ user.id === session.user?.id ? '当前账号' : user.enabled ? '禁用' : '启用' }}</button></td></tr>
    </tbody></table></div><button v-if="more" class="secondary" :disabled="loading" @click="load(true)">加载更多用户</button>
  </section>
  <section v-if="selected" class="panel" aria-label="确认账号状态变更"><h2>确认{{ selected.enabled ? '禁用' : '启用' }} {{ selected.username }}？</h2>
    <p class="muted">禁用会立即撤销此账号的全部会话，重新启用不会恢复旧令牌。</p>
    <form @submit.prevent="submit"><label>操作理由<textarea v-model="reason" required maxlength="500" rows="3" :disabled="saving" /></label><div class="actions"><button :disabled="saving || !reason.trim()">确认{{ selected.enabled ? '禁用' : '启用' }}</button><button type="button" class="secondary" :disabled="saving" @click="selected = null">取消</button></div></form>
  </section>
</template>
