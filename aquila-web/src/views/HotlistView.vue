<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { errorMessage } from '../api/http';
import { addHotlist, listHotlist, removeHotlist, type HotlistItem } from '../api/masterdata';

const securityCode = ref('');
const entries = ref<HotlistItem[]>([]);
const loading = ref(false);
const error = ref('');
const message = ref('');

async function load(): Promise<void> {
  const result = await listHotlist();
  entries.value = result.data ?? [];
}

async function run(work: () => Promise<void>): Promise<void> {
  loading.value = true;
  error.value = '';
  message.value = '';
  try {
    await work();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    loading.value = false;
  }
}

async function add(): Promise<void> {
  await run(async () => {
    await addHotlist(securityCode.value.trim());
    securityCode.value = '';
    await load();
    message.value = 'Manual hotlist entry added.';
  });
}

async function remove(itemId: string): Promise<void> {
  await run(async () => {
    await removeHotlist(itemId);
    await load();
  });
}

onMounted(() => {
  void run(load);
});
</script>

<template>
  <section>
    <h2>Manual hotlist</h2>
    <p>
      Candidate ranking is unavailable until eligible source coverage exists.
      This list contains administrator-entered entries only.
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>

    <fieldset :disabled="loading">
      <legend>Add a security</legend>
      <label for="hotlist-code">Canonical code (SH:600000 or SZ:000001)</label>
      <input id="hotlist-code" v-model="securityCode" @keyup.enter="add" />
      <button type="button" @click="add">Add</button>
    </fieldset>

    <ol>
      <li v-for="entry in entries" :key="entry.itemId">
        {{ entry.securityCode }} <small>{{ entry.source }}</small>
        <button type="button" :disabled="loading" @click="remove(entry.itemId)">Remove</button>
      </li>
    </ol>
    <p v-if="entries.length === 0">No manual entries.</p>
  </section>
</template>
