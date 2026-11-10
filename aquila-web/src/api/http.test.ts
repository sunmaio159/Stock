import { afterEach, describe, expect, it, vi } from 'vitest';
import { request } from './http';

describe('request', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('uses a same-origin API path and returns a valid success envelope', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 0,
      message: 'OK',
      data: { status: 'UP' },
      traceId: 'server-trace',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await request<{ status: string }>('/api/health');

    expect(fetchMock).toHaveBeenCalledWith('/api/health', expect.objectContaining({
      credentials: 'same-origin',
    }));
    expect(result.data).toEqual({ status: 'UP' });
    expect(result.traceId).toBe('server-trace');
  });

  it('rejects absolute URLs before making a request', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(request('https://example.invalid/api/health')).rejects.toThrow(
      'API path must be same-origin',
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('rejects malformed response envelopes', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"code":"bad"}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })));

    await expect(request('/api/health')).rejects.toThrow('Malformed API response');
  });
});
