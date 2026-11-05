import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import Home from './pages/Home.vue'
import Detail from './pages/Detail.vue'
import Login from './pages/Login.vue'
import { identify, session } from './api/client'
import './style.css'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: Home },
    { path: '/post/:id', component: Detail },
    { path: '/login', component: Login },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior() { return { top: 0 } },
})
// 游客可浏览首页与详情；写操作由各页面按 session.user 判断并引导登录。
router.beforeEach(async () => {
  if (session.tokens.accessToken && !session.user) {
    try { await identify() } catch { /* 令牌失效由请求层清会话并跳转 */ }
  }
  return true
})
createApp(App).use(router).mount('#app')
