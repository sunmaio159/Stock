<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { request } from './api/http';

const health = ref('Checking server…');
const error = ref('');

onMounted(async () => {
  try {
    const result = await request<{ status: string }>('/api/health');
    health.value = result.data?.status ?? 'Unavailable';
  } catch {
    health.value = 'Unavailable';
    error.value = 'The local API could not be reached.';
  }
});
</script>

<template>
  <main>
    <h1>Aquila Stock</h1>
    <p>API status: {{ health }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <nav aria-label="Primary">
      <RouterLink to="/watchlist">Watchlist</RouterLink>
      <RouterLink to="/hotlist">Hotlist</RouterLink>
      <RouterLink to="/import">Import</RouterLink>
      <RouterLink to="/sources">Sources</RouterLink>
    </nav>
    <RouterView />
  </main>
</template>
