import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import { getToken } from '@/api/http'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: AppLayout,
      children: [
        {
          path: '',
          name: 'discover',
          component: () => import('@/views/DiscoverView.vue'),
        },
        {
          path: 'shops',
          name: 'shops',
          component: () => import('@/views/ShopsView.vue'),
        },
        {
          path: 'shops/:id',
          name: 'shop-detail',
          component: () => import('@/views/ShopDetailView.vue'),
        },
        {
          path: 'moments',
          name: 'moments',
          component: () => import('@/views/MomentsView.vue'),
        },
        {
          path: 'moments/:id',
          name: 'moment-detail',
          component: () => import('@/views/MomentDetailView.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: 'orders',
          name: 'orders',
          component: () => import('@/views/OrdersView.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: 'engineering',
          name: 'engineering',
          component: () => import('@/views/EngineeringView.vue'),
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
