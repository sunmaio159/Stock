import { defineComponent, h } from 'vue';
import type { RouteRecordRaw } from 'vue-router';
import LoginView from '../views/LoginView.vue';
import SecuritySettingsView from '../views/SecuritySettingsView.vue';
import WatchlistView from '../views/WatchlistView.vue';
import HotlistView from '../views/HotlistView.vue';
import ImportPreviewView from '../views/ImportPreviewView.vue';
import SourceOperationsView from '../views/SourceOperationsView.vue';

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
  { path: '/watchlist', name: 'watchlist', component: WatchlistView, meta: { requiresAuth: true } },
  { path: '/hotlist', name: 'hotlist', component: HotlistView, meta: { requiresAuth: true } },
  {
    path: '/import',
    name: 'import',
    component: ImportPreviewView,
    meta: { requiresAuth: true },
  },
  {
    path: '/sources',
    name: 'sources',
    component: SourceOperationsView,
    meta: { requiresAuth: true },
  },
];
