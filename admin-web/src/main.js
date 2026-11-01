import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import Login from './pages/auth/Login.vue'
import Moderation from './pages/moderation/Moderation.vue'
import Users from './pages/users/Users.vue'
import Traces from './pages/agent-traces/Traces.vue'
import { identify, session } from './api/client'
import './style.css'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: Login },
    { path: '/', redirect: '/moderation' },
    { path: '/moderation', component: Moderation },
    { path: '/users', component: Users },
    { path: '/agent-traces', component: Traces },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
router.beforeEach(async to => {
  if (to.path === '/login') return true
  if (!session.tokens.accessToken) return '/login'
  try { if (!session.user) await identify(); return true } catch { return '/login' }
})
createApp(App).use(router).mount('#app')
