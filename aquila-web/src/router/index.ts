import { createRouter, createWebHistory } from 'vue-router';
import { request } from '../api/http';
import { routeRecords } from './routes';

export const router = createRouter({
  history: createWebHistory(),
  routes: routeRecords,
});

router.beforeEach(async (to) => {
  if (!to.meta.requiresAuth) {
    return true;
  }
  try {
    const result = await request<{ authenticated: boolean; purpose: string }>('/api/auth/session');
    if (!result.data?.authenticated) {
      return '/login';
    }
    if (result.data.purpose === 'RECOVERY' && !to.meta.allowRecoveryContext) {
      return '/security';
    }
    return true;
  } catch {
    return '/login';
  }
});
