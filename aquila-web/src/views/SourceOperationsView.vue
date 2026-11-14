<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { errorMessage } from '../api/http';
import {
  collectSource,
  listCoverage,
  listSources,
  type CoverageFactDto,
  type CrawlResult,
  type SourceConfigDto,
} from '../api/source';

const sources = ref<SourceConfigDto[]>([]);
const selected = ref('');
const coverage = ref<CoverageFactDto[]>([]);
const lastResult = ref<CrawlResult | null>(null);
const loading = ref(false);
const error = ref('');
const message = ref('');

async function loadSources(): Promise<void> {
  const result = await listSources();
  sources.value = result.data ?? [];
  if (sources.value.length > 0 && !selected.value) {
    selected.value = sources.value[0].sourceCode;
  }
}

async function loadCoverage(): Promise<void> {
  if (!selected.value) {
    coverage.value = [];
    return;
  }
  const result = await listCoverage(selected.value);
  coverage.value = result.data ?? [];
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

async function collect(): Promise<void> {
  await run(async () => {
    if (!selected.value) {
      return;
    }
    const result = await collectSource(selected.value);
    lastResult.value = result.data ?? null;
    await loadCoverage();
    if (lastResult.value) {
      message.value = `Collection finished with overall status: ${lastResult.value.coverage.overallStatus}.`;
    }
  });
}

watch(selected, () => {
  lastResult.value = null;
  void run(loadCoverage);
});

onMounted(() => {
  void run(async () => {
    await loadSources();
    await loadCoverage();
  });
});
</script>

<template>
  <section>
    <h2>Source operations</h2>
    <p>
      Licensed collection uses fixtures until G02 authorizes a real source. Coverage must be
      proven complete; recent-N or untraceable sources keep the window as UNKNOWN and never
      COMPLETE.
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>

    <fieldset :disabled="loading">
      <legend>Sources</legend>
      <label for="source-select">Source</label>
      <select id="source-select" v-model="selected">
        <option v-for="source in sources" :key="source.sourceCode" :value="source.sourceCode">
          {{ source.sourceCode }} — {{ source.sourceName }} ({{ source.permissionStatus }})
        </option>
      </select>
      <button type="button" @click="collect">Run collection</button>
    </fieldset>

    <h3>Coverage facts</h3>
    <table v-if="coverage.length > 0">
      <thead>
        <tr>
          <th>Page</th>
          <th>Status</th>
          <th>Reason</th>
          <th>Evidence</th>
          <th>Observed at</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="fact in coverage" :key="fact.pageIndex">
          <td>{{ fact.pageIndex }}</td>
          <td>{{ fact.status }}</td>
          <td>{{ fact.reason ?? '—' }}</td>
          <td>{{ fact.evidenceReference ?? '—' }}</td>
          <td>{{ fact.observedAt }}</td>
        </tr>
      </tbody>
    </table>
    <p v-else>No coverage facts yet for this source.</p>
  </section>
</template>
