import { defaultDeps, type Deps, type PushSender } from '../functions/src/deps';

/** Records pushes instead of calling FCM; [dead] tokens are reported back as invalid. */
export function fakePush(dead: string[] = []) {
  const sent: Array<{ tokens: string[]; data: Record<string, string> }> = [];
  const sender: PushSender = {
    async send(tokens, data) {
      sent.push({ tokens: [...tokens].sort(), data });
      return { invalidTokens: tokens.filter((t) => dead.includes(t)) };
    },
  };
  return { sender, sent };
}

/** Admin-SDK deps pointed at the emulators (emulators:exec sets the *_EMULATOR_HOST variables). */
export function testDeps(overrides: Partial<Deps> = {}): Deps {
  return { ...defaultDeps(), push: fakePush().sender, ...overrides };
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
