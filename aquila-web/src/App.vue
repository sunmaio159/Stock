<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { request } from './api/http';

const health = ref('正在检查服务…');
const error = ref('');

onMounted(async () => {
  try {
    const result = await request<{ status: string }>('/api/health');
    health.value = result.data?.status === 'UP' ? '正常' : '异常';
  } catch {
    health.value = '不可用';
    error.value = '本地接口无法访问。';
  }
});
</script>

<template>
  <main>
    <h1>Aquila 投研工作台</h1>
    <p>接口状态：{{ health }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <nav aria-label="主导航">
      <RouterLink to="/watchlist">自选股</RouterLink>
      <RouterLink to="/hotlist">人工热榜</RouterLink>
      <RouterLink to="/import">数据导入</RouterLink>
      <RouterLink to="/sources">来源运维</RouterLink>
    </nav>
    <RouterView />
  </main>
</template>
