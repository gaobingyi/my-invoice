import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../api/invoice'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { public: true } },
  { path: '/', redirect: '/upload' },
  { path: '/upload', name: 'upload', component: () => import('../views/InvoiceUpload.vue') },
  { path: '/list', name: 'list', component: () => import('../views/InvoiceList.vue') },
  { path: '/exports', name: 'exports', component: () => import('../views/ExportBatchList.vue') },
  { path: '/metrics', name: 'metrics', component: () => import('../views/MetricsDashboard.vue') },
  // pocfile: 兜底路由，未知路径渲染 404 页，避免空白 router-view
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFound.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(to => {
  if (!to.meta.public && !getToken()) {
    return { path: '/login' }
  }
  if (to.path === '/login' && getToken()) {
    return { path: '/upload' }
  }
})

export default router
