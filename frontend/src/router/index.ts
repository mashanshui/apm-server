import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { pinia } from '../stores/pinia'
import { safeRedirect } from '../utils/navigation'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
    },
    {
      path: '/apps',
      name: 'apps',
      meta: { requiresAuth: true },
      component: () => import('../views/AppListView.vue'),
    },
    {
      path: '/apps/new',
      name: 'app-create',
      meta: { requiresAuth: true },
      component: () => import('../views/AppCreateView.vue'),
    },
    {
      path: '/apps/:appId/settings',
      name: 'app-settings',
      meta: { requiresAuth: true, appContext: true },
      component: () => import('../views/AppSettingsView.vue'),
    },
    {
      path: '/apps/:appId/symbols',
      name: 'app-symbols',
      meta: { requiresAuth: true, appContext: true },
      component: () => import('../views/SymbolFilesView.vue'),
    },
    {
      path: '/apps/:appId/crashes',
      name: 'crash-overview',
      meta: { requiresAuth: true, appContext: true },
      component: () => import('../views/CrashOverviewView.vue'),
    },
    {
      path: '/apps/:appId/crashes/issues/:fingerprint',
      name: 'crash-issue-events',
      meta: { requiresAuth: true, appContext: true },
      component: () => import('../views/CrashIssueEventsView.vue'),
    },
    {
      path: '/apps/:appId/crashes/events/:eventId',
      name: 'crash-event-detail',
      meta: { requiresAuth: true, appContext: true },
      component: () => import('../views/CrashEventDetailView.vue'),
    },
    {
      path: '/apps/:appId/jank-metrics',
      name: 'jank-metrics',
      meta: { requiresAuth: true, appContext: true },
      component: () => import('../views/JankMetricsView.vue'),
    },
    {
      path: '/apps/:appId/memory-metrics',
      name: 'memory-metrics',
      meta: { requiresAuth: true, appContext: true },
      component: () => import('../views/MemoryMetricsView.vue'),
    },
    {
      path: '/apps/:appId/memory-leaks',
      name: 'memory-leaks',
      meta: { requiresAuth: true, appContext: true },
      component: () => import('../views/MemoryLeakReportsView.vue'),
    },
    {
      path: '/apps/:appId/janks',
      name: 'jank-issues',
      meta: { requiresAuth: true, appContext: true },
      component: () => import('../views/JankIssuesView.vue'),
    },
    {
      path: '/apps/:appId/janks/issues/:fingerprint',
      name: 'jank-issue-events',
      meta: { requiresAuth: true, appContext: true },
      component: () => import('../views/JankIssueEventsView.vue'),
    },
    {
      path: '/apps/:appId/janks/events/:eventId',
      name: 'jank-event-detail',
      meta: { requiresAuth: true, appContext: true },
      component: () => import('../views/JankEventDetailView.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      meta: { requiresAuth: true },
      component: () => import('../views/NotFoundView.vue'),
    },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore(pinia)
  await session.initialize()
  if (to.name === 'login') {
    return session.isAuthenticated ? safeRedirect(to.query.redirect) : true
  }
  if (to.meta.requiresAuth && !session.isAuthenticated) {
    return {
      name: 'login',
      query: { redirect: safeRedirect(to.fullPath) },
    }
  }
  return true
})

export default router
