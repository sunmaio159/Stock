<script setup lang="ts">
import { ref } from 'vue';
import { errorMessage } from '../api/http';
import {
  commitImport,
  previewImport,
  type ImportPreview,
  type ImportResult,
} from '../api/masterdata';

const fileName = ref('watchlist.txt');
const content = ref('');
const idempotencyKey = ref('');
const preview = ref<ImportPreview | null>(null);
const result = ref<ImportResult | null>(null);
const loading = ref(false);
const error = ref('');
const message = ref('');

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

async function runPreview(): Promise<void> {
  await run(async () => {
    const response = await previewImport(fileName.value.trim(), content.value);
    preview.value = response.data;
    result.value = null;
    idempotencyKey.value = `import-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    message.value = 'Preview ready. Review row errors before committing.';
  });
}

async function commit(): Promise<void> {
  await run(async () => {
    if (!preview.value || !idempotencyKey.value) {
      throw new Error('Create a preview first.');
    }
    const response = await commitImport(preview.value.batchId, idempotencyKey.value);
    result.value = response.data;
    message.value = 'Import committed.';
  });
}
</script>

<template>
  <section>
    <h2>Import watchlist</h2>
    <p>One canonical code per line. Rows with errors are reported but never committed.</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>

    <fieldset :disabled="loading">
      <legend>Source</legend>
      <label for="import-file">File name</label>
      <input id="import-file" v-model="fileName" />
      <label for="import-content">Rows</label>
      <textarea id="import-content" v-model="content" rows="8" cols="40"></textarea>
      <button type="button" @click="runPreview">Preview</button>
    </fieldset>

    <template v-if="preview">
      <h3>Preview</h3>
      <p>
        {{ preview.rowCount }} rows, {{ preview.errorCount }} errors,
        batch {{ preview.batchId }}, status {{ preview.status }}
      </p>
      <table>
        <thead>
          <tr><th>Row</th><th>Input</th><th>Code</th><th>Error</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in preview.rows" :key="row.rowNo">
            <td>{{ row.rowNo }}</td>
            <td>{{ row.rawText }}</td>
            <td>{{ row.normalizedCode ?? '—' }}</td>
            <td>{{ row.errorCode ?? '—' }}</td>
          </tr>
        </tbody>
      </table>

      <fieldset :disabled="loading">
        <legend>Commit</legend>
        <label for="import-key">Idempotency key</label>
        <input id="import-key" v-model="idempotencyKey" />
        <button type="button" @click="commit">Commit atomically</button>
      </fieldset>
      <p v-if="result" role="status">
        Committed {{ result.committedRows }} rows ({{ result.status }}).
      </p>
    </template>
  </section>
</template>
