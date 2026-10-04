import { createRouter, createWebHistory, type Router } from 'vue-router'
import HomeView from '../views/HomeView.vue'

// W0 骨架仅一屏，验证工程可构建可运行；业务页面在 W1+ 补齐
export const router: Router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView }
  ]
})

export default router
