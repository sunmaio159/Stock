import { request, type Result } from './http';

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface SecurityDto {
  securityCode: string;
  exchange: string;
  code: string;
  securityName: string;
  securityType: string;
  status: string;
}

export interface WatchlistGroup {
  groupId: string;
  groupName: string;
  sortOrder: number;
  version: number;
}

export interface WatchlistItem {
  itemId: string;
  groupId: string;
  securityCode: string;
  sortOrder: number;
}

export interface HotlistItem {
  itemId: string;
  securityCode: string;
  source: string;
  sortOrder: number;
}

export interface ImportRow {
  rowNo: number;
  rawText: string;
  normalizedCode: string | null;
  errorCode: string | null;
}

export interface ImportPreview {
  batchId: string;
  fileName: string;
  status: string;
  rowCount: number;
  errorCount: number;
  rows: ImportRow[];
}

export interface ImportResult {
  batchId: string;
  committedRows: number;
  status: string;
}

function write<T>(path: string, method: string, body: unknown): Promise<Result<T>> {
  return request<T>(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

export function searchSecurities(keyword: string, page = 0, size = 20) {
  const query = new URLSearchParams({ keyword, page: String(page), size: String(size) });
  return request<Page<SecurityDto>>(`/api/securities?${query.toString()}`);
}

export function listGroups() {
  return request<WatchlistGroup[]>('/api/watchlist/groups');
}

export function createGroup(groupName: string) {
  return write<WatchlistGroup>('/api/watchlist/groups', 'POST', { groupName });
}

export function deleteGroup(groupId: string) {
  return request<boolean>(`/api/watchlist/groups/${encodeURIComponent(groupId)}`, { method: 'DELETE' });
}

export function listItems(groupId: string) {
  return request<WatchlistItem[]>(`/api/watchlist/groups/${encodeURIComponent(groupId)}/items`);
}

export function addItem(groupId: string, securityCode: string, idempotencyKey?: string) {
  return write<WatchlistItem>(`/api/watchlist/groups/${encodeURIComponent(groupId)}/items`, 'POST',
    { securityCode, idempotencyKey: idempotencyKey ?? null });
}

export function removeItem(itemId: string) {
  return request<boolean>(`/api/watchlist/items/${encodeURIComponent(itemId)}`, { method: 'DELETE' });
}

export function reorder(groupId: string, expectedVersion: number, itemIds: string[]) {
  return write<WatchlistGroup>(`/api/watchlist/groups/${encodeURIComponent(groupId)}/order`, 'PUT',
    { expectedVersion, itemIds });
}

export function listHotlist() {
  return request<HotlistItem[]>('/api/hotlist');
}

export function addHotlist(securityCode: string) {
  return write<HotlistItem>('/api/hotlist', 'POST', { securityCode });
}

export function removeHotlist(itemId: string) {
  return request<boolean>(`/api/hotlist/${encodeURIComponent(itemId)}`, { method: 'DELETE' });
}

export function previewImport(fileName: string, content: string) {
  return write<ImportPreview>('/api/imports/preview', 'POST', { fileName, content });
}

export function getImportBatch(batchId: string) {
  return request<ImportPreview>(`/api/imports/${encodeURIComponent(batchId)}`);
}

export function commitImport(batchId: string, idempotencyKey: string) {
  return write<ImportResult>(`/api/imports/${encodeURIComponent(batchId)}/commit`, 'POST',
    { idempotencyKey });
}
