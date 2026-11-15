<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { request } from '../api/http';

type Mode = 'login' | 'recovery' | 'bootstrap' | 'codes';
interface AuthResponse {
  authenticated: boolean;
  username: string;
  purpose: string;
  recoveryCodes: string[];
}

const router = useRouter();
const mode = ref<Mode>('login');
const username = ref('');
const password = ref('');
const code = ref('');
const bootstrapToken = ref('');
const loading = ref(false);
const error = ref('');
const recoveryCodes = ref<string[]>([]);

async function submit(): Promise<void> {
  loading.value = true;
  error.value = '';
  try {
    if (mode.value === 'bootstrap') {
      await request('/api/auth/bootstrap/context', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ bootstrapToken: bootstrapToken.value }),
      });
      const result = await request<AuthResponse>('/api/auth/bootstrap/confirm', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: code.value }),
      });
      recoveryCodes.value = result.data?.recoveryCodes ?? [];
      bootstrapToken.value = '';
      code.value = '';
      mode.value = 'codes';
      return;
    }

    if (mode.value === 'recovery') {
      const result = await request<AuthResponse>('/api/auth/recovery-login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: username.value,
          password: password.value,
          recoveryCode: code.value,
        }),
      });
      if (result.data?.purpose === 'RECOVERY') {
        await router.replace('/security');
      }
      return;
    }

    await request<AuthResponse>('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: username.value,
        password: password.value,
        totpCode: code.value,
      }),
    });
    await router.replace('/');
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : '';
    error.value = message.includes('Too many failed')
      ? '尝试次数过多，请等待早期失败记录过期后再试。'
      : '认证失败，请检查账号、密码和验证码后重试。';
  } finally {
    password.value = '';
    loading.value = false;
  }
}

function showRecoveryCodes(): void {
  recoveryCodes.value = [];
  void router.replace('/');
}
</script>

<template>
  <main class="auth-card">
    <h1>Aquila 投研工作台</h1>
    <p v-if="mode === 'codes'" role="status">
      请立即保存以下恢复码：仅显示一次，之后无法再次查看。
    </p>
    <template v-if="mode === 'codes'">
      <ul class="recovery-codes">
        <li v-for="recoveryCode in recoveryCodes" :key="recoveryCode">{{ recoveryCode }}</li>
      </ul>
      <button type="button" @click="showRecoveryCodes">我已保存恢复码</button>
    </template>
    <form v-else @submit.prevent="submit">
      <h2 v-if="mode === 'login'">登录</h2>
      <h2 v-else-if="mode === 'recovery'">使用恢复码重新登录</h2>
      <h2 v-else>首次初始化管理员</h2>

      <template v-if="mode !== 'bootstrap'">
        <label>
          管理员账号
          <input v-model="username" autocomplete="username" placeholder="请输入管理员账号" required>
        </label>
        <label>
          密码
          <input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" required>
        </label>
      </template>
      <label v-if="mode === 'bootstrap'">
        一次性初始化令牌
        <input v-model="bootstrapToken" autocomplete="off" placeholder="粘贴本地生成的一次性令牌" required>
      </label>
      <label v-if="mode !== 'recovery'">
        动态验证码（6 位）
        <input
          v-model="code"
          inputmode="numeric"
          autocomplete="one-time-code"
          placeholder="请输入 6 位动态验证码"
          pattern="[0-9]{6}"
          minlength="6"
          maxlength="6"
          required
        >
      </label>
      <label v-else>
        一次性恢复码
        <input v-model="code" autocomplete="off" placeholder="请输入一次性恢复码" required>
      </label>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="loading" role="status">正在验证…</p>
      <button type="submit" :disabled="loading">
        {{ loading ? '请稍候…' : mode === 'bootstrap' ? '确认初始化' : '登录' }}
      </button>
    </form>

    <nav v-if="mode !== 'codes'" aria-label="认证方式">
      <button v-if="mode === 'login'" type="button" @click="mode = 'recovery'; code = ''; error = ''">
        使用恢复码
      </button>
      <button v-if="mode === 'login'" type="button" @click="mode = 'bootstrap'; code = ''; error = ''">
        初始化首个管理员
      </button>
      <button v-if="mode !== 'login'" type="button" @click="mode = 'login'; code = ''; error = ''">
        返回登录
      </button>
    </nav>
    <p class="transport-warning">
      本地 HTTP 通信未加密，请仅在可信网络中使用，直至配置 HTTPS。
    </p>
  </main>
</template>

<style scoped>
.auth-card {
  max-width: 28rem;
  margin: 3rem auto;
  padding: 1.5rem;
}

form,
label {
  display: grid;
  gap: 0.75rem;
}

label {
  margin-block: 0.75rem;
}

input,
button {
  min-height: 2.5rem;
}

nav {
  display: flex;
  gap: 0.75rem;
  margin-top: 1rem;
}

.recovery-codes {
  columns: 2;
  font-family: monospace;
}

.transport-warning {
  margin-top: 2rem;
  font-size: 0.875rem;
}
</style>
