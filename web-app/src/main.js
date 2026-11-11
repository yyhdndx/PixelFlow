import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import Home from './pages/Home.vue'
import Detail from './pages/Detail.vue'
import Login from './pages/Login.vue'
import Search from './pages/Search.vue'
import Me from './pages/Me.vue'
import EditProfile from './pages/EditProfile.vue'
import Favorites from './pages/Favorites.vue'
import Following from './pages/Following.vue'
import Followers from './pages/Followers.vue'
import Notifications from './pages/Notifications.vue'
import Interests from './pages/Interests.vue'
import InterestHistory from './pages/InterestHistory.vue'
import Author from './pages/Author.vue'
import MyWorks from './pages/MyWorks.vue'
import WorkEditor from './pages/WorkEditor.vue'
import NewWork from './pages/NewWork.vue'
import AutoReplies from './pages/AutoReplies.vue'
import Messages from './pages/Messages.vue'
import Copilot from './pages/Copilot.vue'
import { identify, session } from './api/client'
import './style.css'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: Home },
    { path: '/post/:id', component: Detail },
    { path: '/login', component: Login },
    { path: '/search', component: Search },
    { path: '/me', component: Me, meta: { requiresAuth: true } },
    { path: '/me/edit', component: EditProfile, meta: { requiresAuth: true } },
    { path: '/me/works', component: MyWorks, meta: { requiresAuth: true } },
    { path: '/me/works/new', component: NewWork, meta: { requiresAuth: true } },
    { path: '/me/works/:id/edit', component: WorkEditor, meta: { requiresAuth: true } },
    { path: '/me/works/:id/auto-replies', component: AutoReplies, meta: { requiresAuth: true } },
    { path: '/me/favorites', component: Favorites, meta: { requiresAuth: true } },
    { path: '/me/following', component: Following, meta: { requiresAuth: true } },
    { path: '/me/followers', component: Followers, meta: { requiresAuth: true } },
    { path: '/me/notifications', component: Notifications, meta: { requiresAuth: true } },
    { path: '/me/interests', component: Interests, meta: { requiresAuth: true } },
    { path: '/me/interests/history', component: InterestHistory, meta: { requiresAuth: true } },
    { path: '/me/messages', component: Messages, meta: { requiresAuth: true } },
    { path: '/me/messages/:id', component: Messages, meta: { requiresAuth: true } },
    { path: '/assistant/:screen/:id?', component: Copilot, meta: { requiresAuth: true } },
    { path: '/author/:id', component: Author, meta: { requiresAuth: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior() { return { top: 0 } },
})
router.beforeEach(async to => {
  if (session.tokens.accessToken && !session.user) {
    try { await identify() } catch {}
  }
  if (to.meta.requiresAuth && !session.user) return { path: '/login', query: { from: to.fullPath } }
  return true
})
createApp(App).use(router).mount('#app')
