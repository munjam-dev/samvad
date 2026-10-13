import { describe, expect, it, vi } from 'vitest';

const info = vi.fn();
vi.mock('firebase-functions/logger', () => ({ info }));

const { logEvent } = await import('../src/lib/logger');

describe('logEvent', () => {
  it('drops phone and token fields and keeps correlationId', () => {
    logEvent('account_deleted', {
      correlationId: 'c-1',
      phone: '+919999900001',
      idToken: 'secret-token',
    } as any);
    expect(info).toHaveBeenCalledTimes(1);
    const [message, payload] = info.mock.calls[0];
    expect(message).toBe('account_deleted');
    expect(payload).toEqual({ event: 'account_deleted', correlationId: 'c-1' });
    expect(JSON.stringify(info.mock.calls)).not.toMatch(/919999900001|secret-token/);
  });
});
