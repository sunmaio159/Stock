import { request } from './http';

export interface SourceConfigDto {
  sourceCode: string;
  sourceName: string;
  permissionStatus: string;
  validFrom: string;
  validTo: string | null;
}

export interface CoverageFactDto {
  pageIndex: number;
  intervalStart: string | null;
  intervalEnd: string | null;
  status: string;
  reason: string | null;
  evidenceReference: string | null;
  observedAt: string;
}

export interface CoverageState {
  runId: string;
  overallStatus: string;
  pagesTotal: number;
  pagesComplete: number;
  pagesPartial: number;
  pagesUnknown: number;
  pagesUnrecoverable: number;
  observedAt: string;
}

export interface CrawlResult {
  runId: string;
  coverage: CoverageState;
}

export function listSources() {
  return request<SourceConfigDto[]>('/api/sources');
}

export function listCoverage(sourceCode: string) {
  return request<CoverageFactDto[]>(
    `/api/sources/${encodeURIComponent(sourceCode)}/coverage`,
  );
}

export function collectSource(
  sourceCode: string,
  scope?: string,
  start?: string,
  end?: string,
) {
  const params = new URLSearchParams();
  if (scope) params.set('scope', scope);
  if (start) params.set('start', start);
  if (end) params.set('end', end);
  const query = params.toString();
  return request<CrawlResult>(
    `/api/sources/${encodeURIComponent(sourceCode)}/collect${query ? `?${query}` : ''}`,
    { method: 'POST' },
  );
}
