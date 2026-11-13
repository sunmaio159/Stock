export interface Result<T> {
  code: number;
  message: string;
  data: T | null;
  traceId: string;
}

export class ApiRequestError extends Error {
  readonly status: number;
  readonly code: number;

  constructor(message: string, status: number, code: number) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = status;
    this.code = code;
  }
}

export function errorMessage(cause: unknown): string {
  if (cause instanceof ApiRequestError) {
    return `[${cause.code}] ${cause.message}`;
  }
  return cause instanceof Error ? cause.message : 'The request failed.';
}

export async function request<T>(
  path: string,
  init: RequestInit = {},
): Promise<Result<T>> {
  if (!path.startsWith('/api/') || path.startsWith('//')) {
    throw new Error('API path must be same-origin');
  }

  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (isWriteMethod(init.method) && path !== '/api/auth/csrf') {
    headers.set('X-CSRF-Token', await fetchCsrfToken());
  }

  const response = await fetch(path, {
    ...init,
    credentials: 'same-origin',
    headers,
  });

  return readResult<T>(response);
}

async function fetchCsrfToken(): Promise<string> {
  const response = await fetch('/api/auth/csrf', {
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { Accept: 'application/json' },
  });
  const result = await readResult<{ token: string }>(response);
  if (!result.data || typeof result.data.token !== 'string' || result.data.token.length === 0) {
    throw new Error('Malformed CSRF response');
  }
  return result.data.token;
}

async function readResult<T>(response: Response): Promise<Result<T>> {
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new Error('Malformed API response');
  }

  if (!isResult<T>(payload)) {
    throw new Error('Malformed API response');
  }
  if (!response.ok) {
    throw new ApiRequestError(
      payload.message || `HTTP ${response.status}`,
      response.status,
      payload.code,
    );
  }
  return payload;
}

function isWriteMethod(method?: string): boolean {
  return ['POST', 'PUT', 'PATCH', 'DELETE'].includes((method ?? 'GET').toUpperCase());
}

function isResult<T>(value: unknown): value is Result<T> {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const result = value as Record<string, unknown>;
  return typeof result.code === 'number'
    && typeof result.message === 'string'
    && Object.hasOwn(result, 'data')
    && typeof result.traceId === 'string';
}
