<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { request } from '../api/http';

interface SessionInfo {
  authenticated: boolean;
  username: string;
  purpose: 'ADMIN' | 'RECOVERY';
  recoveryCodes: string[];
}

interface RecoveryCodes {
  codes: string[];
  shownOnce: boolean;
  requiresLogin: boolean;
}

interface TotpEnrollment {
  secret: string;
  otpauthUri: string;
}

type AdminAction = 'rotate-totp' | 'rotate-codes';

const router = useRouter();
const session = ref<SessionInfo | null>(null);
const action = ref<AdminAction>('rotate-totp');
const password = ref('');
const code = ref('');
const loading = ref(false);
const error = ref('');
const message = ref('');
const enrollment = ref<TotpEnrollment | null>(null);
const displayedCodes = ref<RecoveryCodes | null>(null);

onMounted(async () => {
  loading.value = true;
  try {
    const result = await request<SessionInfo>('/api/auth/session');
    session.value = result.data;
  } catch {
    error.value = 'Your session is unavailable. Sign in again.';
  } finally {
    loading.value = false;
  }
});

async function reauthenticate(): Promise<void> {
  loading.value = true;
  error.value = '';
  message.value = '';
  try {
    await request('/api/auth/reauth', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: password.value, totpCode: code.value }),
    });
    password.value = '';
    code.value = '';
    if (action.value === 'rotate-totp') {
      const result = await request<TotpEnrollment>('/api/auth/totp/setup', { method: 'POST' });
      enrollment.value = result.data;
      message.value = 'Add this new authenticator secret, then confirm with its six-digit code.';
    } else {
      const result = await request<RecoveryCodes>('/api/auth/recovery-codes/rotate', { method: 'POST' });
      displayedCodes.value = result.data;
    }
  } catch {
    error.value = 'Reauthentication failed or the security context expired. Try again.';
  } finally {
    loading.value = false;
  }
}

async function startRecoveryRebind(): Promise<void> {
  loading.value = true;
  error.value = '';
  try {
    const result = await request<TotpEnrollment>('/api/auth/recovery/rebind/start', { method: 'POST' });
    enrollment.value = result.data;
    message.value = 'Add this new authenticator secret, then confirm with its six-digit code.';
  } catch {
    error.value = 'The recovery context expired. Sign in with a recovery code again.';
  } finally {
    loading.value = false;
  }
}

async function confirmEnrollment(): Promise<void> {
  loading.value = true;
  error.value = '';
  try {
    const path = session.value?.purpose === 'RECOVERY'
      ? '/api/auth/recovery/rebind/confirm'
      : '/api/auth/totp/confirm';
    const result = await request<RecoveryCodes>(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: code.value }),
    });
    displayedCodes.value = result.data;
    enrollment.value = null;
    code.value = '';
  } catch {
    error.value = 'The code is invalid or the setup context expired.';
  } finally {
    loading.value = false;
  }
}

async function finish(): Promise<void> {
  const mustSignIn = displayedCodes.value?.requiresLogin ?? false;
  displayedCodes.value = null;
  if (mustSignIn) {
    await router.replace('/login');
  }
}
</script>

<template>
  <main class="security-card">
    <h1>Security settings</h1>
    <p v-if="session">Signed in as {{ session.username }} ({{ session.purpose.toLowerCase() }} context).</p>
    <p v-if="loading" role="status">Loading security settings…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>

    <section v-if="displayedCodes" aria-labelledby="recovery-codes-heading">
      <h2 id="recovery-codes-heading">Save your recovery codes now</h2>
      <p>These codes are shown once. Keep them somewhere private and offline.</p>
      <ul class="recovery-codes">
        <li v-for="recoveryCode in displayedCodes.codes" :key="recoveryCode">{{ recoveryCode }}</li>
      </ul>
      <button type="button" @click="finish">I saved these codes</button>
    </section>

    <section v-else-if="session?.purpose === 'RECOVERY'">
      <h2>Restore authenticator access</h2>
      <button v-if="!enrollment" type="button" :disabled="loading" @click="startRecoveryRebind">
        Start authenticator rebind
      </button>
    </section>

    <section v-else-if="session?.purpose === 'ADMIN' && !enrollment">
      <h2>Reauthenticate to change security settings</h2>
      <label>
        Security action
        <select v-model="action">
          <option value="rotate-totp">Replace authenticator</option>
          <option value="rotate-codes">Rotate recovery codes</option>
        </select>
      </label>
      <form @submit.prevent="reauthenticate">
        <label>
          Password
          <input v-model="password" type="password" autocomplete="current-password" required>
        </label>
        <label>
          Current authenticator code
          <input v-model="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" required>
        </label>
        <button type="submit" :disabled="loading">Reauthenticate and continue</button>
      </form>
    </section>

    <section v-if="enrollment && !displayedCodes">
      <h2>Authenticator setup</h2>
      <p>Enter this secret in your authenticator app. It is not stored in browser storage.</p>
      <p class="secret">{{ enrollment.secret }}</p>
      <label>
        Authenticator code
        <input v-model="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" required>
      </label>
      <button type="button" :disabled="loading || code.length !== 6" @click="confirmEnrollment">
        Confirm and replace recovery codes
      </button>
    </section>
    <p class="transport-warning">
      Local HTTP traffic is not encrypted. Use only on a trusted network until HTTPS is configured.
    </p>
  </main>
</template>

<style scoped>
.security-card {
  max-width: 34rem;
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
button,
select {
  min-height: 2.5rem;
}

.secret,
.recovery-codes {
  font-family: monospace;
  overflow-wrap: anywhere;
}

.recovery-codes {
  columns: 2;
}

.transport-warning {
  margin-top: 2rem;
  font-size: 0.875rem;
}
</style>
