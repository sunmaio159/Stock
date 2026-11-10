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
      ? 'Too many attempts. Try again after earlier failures age out.'
      : 'Authentication failed. Check the credentials and try again.';
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
    <h1>Aquila Stock</h1>
    <p v-if="mode === 'codes'" role="status">
      Save these recovery codes now. They are shown once and cannot be viewed again.
    </p>
    <template v-if="mode === 'codes'">
      <ul class="recovery-codes">
        <li v-for="recoveryCode in recoveryCodes" :key="recoveryCode">{{ recoveryCode }}</li>
      </ul>
      <button type="button" @click="showRecoveryCodes">I saved these codes</button>
    </template>
    <form v-else @submit.prevent="submit">
      <h2 v-if="mode === 'login'">Sign in</h2>
      <h2 v-else-if="mode === 'recovery'">Recover authenticator access</h2>
      <h2 v-else>Complete first-time setup</h2>

      <template v-if="mode !== 'bootstrap'">
        <label>
          Administrator
          <input v-model="username" autocomplete="username" required>
        </label>
        <label>
          Password
          <input v-model="password" type="password" autocomplete="current-password" required>
        </label>
      </template>
      <label v-if="mode === 'bootstrap'">
        One-time bootstrap token
        <input v-model="bootstrapToken" autocomplete="off" required>
      </label>
      <label v-if="mode !== 'recovery'">
        {{ mode === 'bootstrap' ? 'Authenticator code' : 'Authenticator code' }}
        <input
          v-model="code"
          inputmode="numeric"
          autocomplete="one-time-code"
          pattern="[0-9]{6}"
          minlength="6"
          maxlength="6"
          required
        >
      </label>
      <label v-else>
        One-time recovery code
        <input v-model="code" autocomplete="off" required>
      </label>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="loading" role="status">Checking credentials…</p>
      <button type="submit" :disabled="loading">
        {{ loading ? 'Please wait…' : mode === 'bootstrap' ? 'Confirm setup' : 'Continue' }}
      </button>
    </form>

    <nav v-if="mode !== 'codes'" aria-label="Authentication options">
      <button v-if="mode === 'login'" type="button" @click="mode = 'recovery'; code = ''; error = ''">
        Use a recovery code
      </button>
      <button v-if="mode === 'login'" type="button" @click="mode = 'bootstrap'; code = ''; error = ''">
        Set up the first administrator
      </button>
      <button v-if="mode !== 'login'" type="button" @click="mode = 'login'; code = ''; error = ''">
        Back to sign in
      </button>
    </nav>
    <p class="transport-warning">
      Local HTTP traffic is not encrypted. Use only on a trusted network until HTTPS is configured.
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
