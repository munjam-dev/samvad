import { defaultDeps, type Deps } from '../functions/src/deps';

/** Admin-SDK deps pointed at the emulators (emulators:exec sets the *_EMULATOR_HOST variables). */
export function testDeps(overrides: Partial<Deps> = {}): Deps {
  return { ...defaultDeps(), ...overrides };
}

export function phoneRequest(uid: string, extra: { authTime?: number; provider?: string; data?: unknown } = {}) {
  return {
    auth: {
      uid,
      token: {
        auth_time: extra.authTime ?? Math.floor(Date.now() / 1000),
        firebase: { sign_in_provider: extra.provider ?? 'phone' },
        phone_number: '+919999900001',
      },
    },
    data: extra.data ?? {},
  } as any;
}
