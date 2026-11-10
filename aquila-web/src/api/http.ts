export interface Result<T> {
  code: number;
  message: string;
  data: T | null;
  traceId: string;
}

export async function request<T>(
  path: string,
  init: RequestInit = {},
): Promise<Result<T>> {
  if (!path.startsWith('/api/') || path.startsWith('//')) {
    throw new Error('API path must be same-origin');
  }

  const response = await fetch(path, {
    ...init,
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      ...init.headers,
    },
  });

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
    throw new Error(payload.message || `HTTP ${response.status}`);
  }
  return payload;
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
