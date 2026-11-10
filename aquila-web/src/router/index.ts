import { createRouter, createWebHistory } from 'vue-router';
import { defineComponent, h } from 'vue';

const Home = defineComponent({
  name: 'HomeView',
  setup: () => () => h('p', 'Local stock research foundation'),
});

export const router = createRouter({
  history: createWebHistory(),
  routes: [{ path: '/', component: Home }],
});
