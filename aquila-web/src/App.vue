<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from './api/http'

const pingResult = ref<string>('未探测')
const pingError = ref<string>('')

async function doPing() {
  pingError.value = ''
  try {
    pingResult.value = JSON.stringify(await api.ping())
  } catch (e) {
    pingError.value = String(e)
    pingResult.value = '失败（后端未启动或需登录，属预期）'
  }
}

onMounted(() => {
  // 不在挂载即调用，避免无后端时报错刷屏；由用户点击触发
})
</script>

<template>
  <el-container style="padding: 24px">
    <el-header>
      <h2>Aquila 投研工具 · W0 工程骨架</h2>
    </el-header>
    <el-main>
      <el-card>
        <p>前端骨架已就绪（Vue3 + TS + Vite + Element Plus + Pinia）。</p>
        <el-button type="primary" @click="doPing">探测后端 /api/ping</el-button>
        <div style="margin-top: 12px">
          <strong>结果：</strong>{{ pingResult }}
          <div v-if="pingError" style="color: #e6a23c">{{ pingError }}</div>
        </div>
      </el-card>
    </el-main>
  </el-container>
</template>

<style>
/* 涨跌色等合规配色一律走 CSS 变量 token（F-SEC-06），此处仅占位 */
:root {
  --aquila-up: #d03a3a;
  --aquila-down: #1a9e5c;
}
</style>
