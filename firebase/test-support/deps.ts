import { defaultDeps, type Deps, type PushSender } from '../functions/src/deps';
import type { LiveKitService } from '../functions/src/lib/livekit';

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

/** A fake LiveKit service: mints a traceable fake token instead of calling the real SDK. */
export function fakeLiveKit(): { service: LiveKitService; closed: string[] } {
  const closed: string[] = [];
  const service: LiveKitService = {
    url: 'wss://fake.livekit.cloud',
    async mintToken(req) {
      return `fake-token.${req.room}.${req.identity}.${req.video ? 'video' : 'audio'}`;
    },
    async closeRoom(room) {
      closed.push(room);
    },
  };
  return { service, closed };
}

export function phoneRequest(
  uid: string,
  extra: { authTime?: number; provider?: string; data?: unknown; claims?: Record<string, unknown> } = {},
) {
  return {
    auth: {
      uid,
      token: {
        auth_time: extra.authTime ?? Math.floor(Date.now() / 1000),
        firebase: { sign_in_provider: extra.provider ?? 'phone' },
        phone_number: '+919999900001',
        ...extra.claims,
      },
    },
    data: extra.data ?? {},
  } as any;
}
