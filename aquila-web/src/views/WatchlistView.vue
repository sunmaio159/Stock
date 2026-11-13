<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { errorMessage } from '../api/http';
import {
  addItem,
  createGroup,
  listGroups,
  listItems,
  removeItem,
  reorder,
  searchSecurities,
  type SecurityDto,
  type WatchlistGroup,
  type WatchlistItem,
} from '../api/masterdata';

const keyword = ref('');
const results = ref<SecurityDto[]>([]);
const groups = ref<WatchlistGroup[]>([]);
const selectedGroupId = ref('');
const items = ref<WatchlistItem[]>([]);
const groupName = ref('');
const loading = ref(false);
const error = ref('');
const message = ref('');

const selectedGroup = (): WatchlistGroup | undefined =>
  groups.value.find((group) => group.groupId === selectedGroupId.value);

async function loadGroups(): Promise<void> {
  const result = await listGroups();
  groups.value = result.data ?? [];
  if (!selectedGroupId.value && groups.value.length > 0) {
    selectedGroupId.value = groups.value[0].groupId;
  }
  await loadItems();
}

async function loadItems(): Promise<void> {
  if (!selectedGroupId.value) {
    items.value = [];
    return;
  }
  const result = await listItems(selectedGroupId.value);
  items.value = result.data ?? [];
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

async function search(): Promise<void> {
  await run(async () => {
    const result = await searchSecurities(keyword.value);
    results.value = result.data?.items ?? [];
  });
}

async function makeGroup(): Promise<void> {
  await run(async () => {
    await createGroup(groupName.value.trim());
    groupName.value = '';
    await loadGroups();
    message.value = 'Group created.';
  });
}

async function add(securityCode: string): Promise<void> {
  await run(async () => {
    if (!selectedGroupId.value) {
      throw new Error('Create or select a group first.');
    }
    await addItem(selectedGroupId.value, securityCode);
    await loadItems();
    message.value = `${securityCode} added.`;
  });
}

async function remove(itemId: string): Promise<void> {
  await run(async () => {
    await removeItem(itemId);
    await loadItems();
  });
}

async function move(index: number, offset: number): Promise<void> {
  const target = index + offset;
  const group = selectedGroup();
  if (!group || target < 0 || target >= items.value.length) {
    return;
  }
  await run(async () => {
    const reordered = [...items.value];
    const [moved] = reordered.splice(index, 1);
    reordered.splice(target, 0, moved);
    const result = await reorder(group.groupId, group.version, reordered.map((item) => item.itemId));
    groups.value = groups.value.map((entry) =>
      entry.groupId === group.groupId ? result.data ?? entry : entry);
    items.value = reordered;
    message.value = 'Order saved.';
  });
}

onMounted(() => {
  void run(loadGroups);
});
</script>

<template>
  <section>
    <h2>Watchlists</h2>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>

    <fieldset :disabled="loading">
      <legend>Search securities</legend>
      <label for="security-keyword">Name, alias or SH:/SZ: code</label>
      <input id="security-keyword" v-model="keyword" @keyup.enter="search" />
      <button type="button" @click="search">Search</button>
      <ul>
        <li v-for="security in results" :key="security.securityCode">
          {{ security.securityCode }} — {{ security.securityName }}
          <button type="button" @click="add(security.securityCode)">Add</button>
        </li>
      </ul>
      <p v-if="results.length === 0">No results yet.</p>
    </fieldset>

    <fieldset :disabled="loading">
      <legend>Groups</legend>
      <label for="group-name">New group name</label>
      <input id="group-name" v-model="groupName" />
      <button type="button" @click="makeGroup">Create group</button>
      <label for="group-select">Active group</label>
      <select id="group-select" v-model="selectedGroupId" @change="loadItems">
        <option v-for="group in groups" :key="group.groupId" :value="group.groupId">
          {{ group.groupName }} (v{{ group.version }})
        </option>
      </select>
      <p v-if="groups.length === 0">Create a group to start adding securities.</p>
    </fieldset>

    <fieldset :disabled="loading">
      <legend>Items</legend>
      <ol>
        <li v-for="(item, index) in items" :key="item.itemId">
          {{ item.securityCode }}
          <button type="button" :disabled="index === 0" @click="move(index, -1)">Up</button>
          <button type="button" :disabled="index === items.length - 1" @click="move(index, 1)">Down</button>
          <button type="button" @click="remove(item.itemId)">Remove</button>
        </li>
      </ol>
      <p v-if="items.length === 0">This group is empty.</p>
    </fieldset>
  </section>
</template>
