import { defineComponent, h } from 'vue';
import type { RouteRecordRaw } from 'vue-router';
import LoginView from '../views/LoginView.vue';
import SecuritySettingsView from '../views/SecuritySettingsView.vue';

const Home = defineComponent({
  name: 'HomeView',
  setup: () => () => h('p', 'Local stock research foundation'),
});

export const routeRecords: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: LoginView, meta: { requiresAuth: false } },
  {
    path: '/security',
    name: 'security',
    component: SecuritySettingsView,
    meta: { requiresAuth: true, allowRecoveryContext: true },
  },
  { path: '/', name: 'home', component: Home, meta: { requiresAuth: true } },
];
