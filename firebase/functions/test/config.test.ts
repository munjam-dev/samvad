import { describe, expect, it } from 'vitest';
import { buildCallableOptions } from '../src/config';

describe('callable config', () => {
  it('enforces App Check with replay protection in asia-south1 by default', () => {
    expect(buildCallableOptions({})).toEqual({
      region: 'asia-south1',
      enforceAppCheck: true,
      consumeAppCheckToken: true,
    });
  });

  it('relaxes App Check only in the Functions emulator', () => {
    const opts = buildCallableOptions({ FUNCTIONS_EMULATOR: 'true' });
    expect(opts.enforceAppCheck).toBe(false);
    expect(opts.consumeAppCheckToken).toBe(false);
  });
});
