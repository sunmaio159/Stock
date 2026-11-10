import { describe, expect, it } from 'vitest';
import { routeRecords } from './routes';

describe('authentication routes', () => {
  it('leaves login reachable anonymously and protects security settings', () => {
    const login = routeRecords.find((route) => route.path === '/login');
    const security = routeRecords.find((route) => route.path === '/security');

    expect(login?.meta?.requiresAuth).toBe(false);
    expect(security?.meta?.requiresAuth).toBe(true);
    expect(security?.meta?.allowRecoveryContext).toBe(true);
  });
});
