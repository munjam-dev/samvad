import { defineSecret } from 'firebase-functions/params';
import { HttpsError } from 'firebase-functions/v2/https';

/**
 * Media for calls and meetings runs on a managed LiveKit project. The server never touches media: it only
 * mints short-lived access tokens (and can close a room). The three secrets live in Firebase Functions
 * secrets (Secret Manager), never in code or in the app:
 *   firebase functions:secrets:set LIVEKIT_URL | LIVEKIT_API_KEY | LIVEKIT_API_SECRET
 */
export const LIVEKIT_URL = defineSecret('LIVEKIT_URL');
export const LIVEKIT_API_KEY = defineSecret('LIVEKIT_API_KEY');
export const LIVEKIT_API_SECRET = defineSecret('LIVEKIT_API_SECRET');
export const LIVEKIT_SECRETS = [LIVEKIT_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET];

/** A token is valid for 10 minutes: long enough to connect, short enough that a leak is nearly useless. */
export const TOKEN_TTL_SECONDS = 10 * 60;

export interface TokenRequest {
  /** The Firebase UID: one identity per person, so a second device replaces the first. */
  identity: string;
  name: string;
  room: string;
  ttlSeconds: number;
  /** Audio calls publish no camera track. */
  video: boolean;
}

/** What the call and meeting handlers need from LiveKit. Tests pass a fake. */
export interface LiveKitService {
  /** The wss:// URL clients connect to. */
  url: string;
  mintToken(req: TokenRequest): Promise<string>;
  /** Disconnects everyone and removes the room. A room that does not exist is not an error. */
  closeRoom(room: string): Promise<void>;
}

export interface LiveKitConfig {
  url: string;
  apiKey: string;
  apiSecret: string;
}

const PLACEHOLDER = 'not-configured';

/**
 * Null when any of the three values is empty, the placeholder word, or the URL is not a LiveKit address.
 * (Firebase will not deploy a function whose secret is missing, so before the LiveKit project exists the owner
 * sets each secret to the word "not-configured"; the app then shows "Calling isn't set up yet".)
 */
export function readLiveKitConfig(values: { url?: string; apiKey?: string; apiSecret?: string }): LiveKitConfig | null {
  const url = (values.url ?? '').trim();
  const apiKey = (values.apiKey ?? '').trim();
  const apiSecret = (values.apiSecret ?? '').trim();
  if (!url || !apiKey || !apiSecret) return null;
  if (url === PLACEHOLDER || apiKey === PLACEHOLDER || apiSecret === PLACEHOLDER) return null;
  if (!/^(wss|https):\/\/[^\s/]+/i.test(url)) return null;
  return { url, apiKey, apiSecret };
}

/** The REST address of a LiveKit project (the SDK's room service talks https, clients talk wss). */
export function httpUrl(url: string): string {
  return url.replace(/^wss:/i, 'https:');
}

export function wsUrl(url: string): string {
  return url.replace(/^https:/i, 'wss:');
}

/** Real LiveKit service, or null while the secrets are not set up. Call only inside a function that binds the secrets. */
export function defaultLiveKit(): LiveKitService | null {
  let config: LiveKitConfig | null = null;
  try {
    config = readLiveKitConfig({ url: LIVEKIT_URL.value(), apiKey: LIVEKIT_API_KEY.value(), apiSecret: LIVEKIT_API_SECRET.value() });
  } catch {
    config = null;
  }
  if (!config) return null;
  const cfg = config;
  return {
    url: wsUrl(cfg.url),
    async mintToken(req) {
      const { AccessToken } = await import('livekit-server-sdk');
      const token = new AccessToken(cfg.apiKey, cfg.apiSecret, {
        identity: req.identity,
        name: req.name,
        ttl: req.ttlSeconds,
      });
      token.addGrant({
        roomJoin: true,
        room: req.room,
        canPublish: true,
        canSubscribe: true,
        canPublishData: false,
        // Audio calls carry no camera; the grant mirrors what the call is for.
        canPublishSources: req.video ? ['microphone', 'camera'] as never : ['microphone'] as never,
      });
      return token.toJwt();
    },
    async closeRoom(room) {
      try {
        const { RoomServiceClient } = await import('livekit-server-sdk');
        await new RoomServiceClient(httpUrl(cfg.url), cfg.apiKey, cfg.apiSecret).deleteRoom(room);
      } catch {
        // Best effort: the room also closes by itself once everyone has left.
      }
    },
  };
}

/** Shared by every handler that needs media: the friendly "not set up yet" answer. */
export function requireLiveKit(livekit: LiveKitService | null): LiveKitService {
  if (!livekit) throw new HttpsError('failed-precondition', 'calls-not-configured');
  return livekit;
}
