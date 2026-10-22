import { defineSecret } from 'firebase-functions/params';
import { HttpsError } from 'firebase-functions/v2/https';

/**
 * GIF and sticker search runs on the server so the provider's API key never ships in the app. The key lives in a
 * Functions secret (Secret Manager):  firebase functions:secrets:set GIPHY_API_KEY
 * To use another provider, implement [GifProvider] (see GIFS_SETUP.md) and return it from [defaultGifProvider].
 */
export const GIPHY_API_KEY = defineSecret('GIPHY_API_KEY');
export const GIF_SECRETS = [GIPHY_API_KEY];

export type GifKind = 'gif' | 'sticker';

/** What the app needs to show and send one result; nothing else from the provider is passed on. */
export interface GifItem {
  id: string;
  title: string;
  /** Small animated preview for the picker grid. */
  previewUrl: string;
  /** The image that is shown in the chat once sent. */
  url: string;
  width: number;
  height: number;
}

export interface GifPage {
  items: GifItem[];
  /** Opaque cursor for the next page, or null at the end. */
  next: string | null;
}

export interface GifProvider {
  readonly id: string;
  search(query: string, kind: GifKind, cursor: string | null): Promise<GifPage>;
  trending(kind: GifKind, cursor: string | null): Promise<GifPage>;
}

/** The slice of fetch the provider needs. Tests pass a fake. */
export type GifFetcher = (url: string, init?: { signal?: AbortSignal }) => Promise<{ status: number; json(): Promise<unknown> }>;

export const PAGE_SIZE = 24;
/** Highest offset GIPHY serves. */
export const MAX_OFFSET = 4999;
/** Content rating asked of the provider: "g" is suitable for all ages. */
export const GIF_RATING = 'g';
export const REQUEST_TIMEOUT_MS = 5000;
export const CACHE_TTL_MS = 60_000;
export const CACHE_MAX_ENTRIES = 200;
export const MAX_QUERY_LENGTH = 50;

const PLACEHOLDER = 'not-configured';

/** Image hosts we accept from a provider (and that the Firestore rules accept in a message). */
const GIPHY_HOST = /^(media[0-9]{0,2}|i)\.giphy\.com$/;

export function isAllowedGifUrl(raw: unknown): raw is string {
  if (typeof raw !== 'string' || raw.length > 500) return false;
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    return false;
  }
  return u.protocol === 'https:' && !u.username && !u.password && !u.port && GIPHY_HOST.test(u.hostname);
}

/** Null when the key is empty or the placeholder word (Firebase will not deploy a function whose secret is unset). */
export function readGifKey(value: string | undefined): string | null {
  const key = (value ?? '').trim();
  if (!key || key === PLACEHOLDER) return null;
  return key;
}

/** Trims, drops control characters, collapses spaces. Throws invalid-argument when empty or too long. */
export function normalizeQuery(raw: unknown): string {
  if (typeof raw !== 'string') throw new HttpsError('invalid-argument', 'query-empty');
  // eslint-disable-next-line no-control-regex
  const q = raw.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim();
  if (!q) throw new HttpsError('invalid-argument', 'query-empty');
  if ([...q].length > MAX_QUERY_LENGTH) throw new HttpsError('invalid-argument', 'query-too-long');
  return q;
}

export function parseKind(raw: unknown): GifKind {
  if (raw === 'gif' || raw === 'sticker') return raw;
  throw new HttpsError('invalid-argument', 'bad-kind');
}

/** An offset as a decimal string, or null for the first page. */
export function parseCursor(raw: unknown): number {
  if (raw === undefined || raw === null || raw === '') return 0;
  if (typeof raw !== 'string' || !/^[0-9]{1,4}$/.test(raw)) throw new HttpsError('invalid-argument', 'bad-cursor');
  const n = Number(raw);
  if (n > MAX_OFFSET) throw new HttpsError('invalid-argument', 'bad-cursor');
  return n;
}

function positiveInt(v: unknown): number | null {
  const n = typeof v === 'string' ? Number(v) : typeof v === 'number' ? v : NaN;
  return Number.isInteger(n) && n > 0 && n <= 10000 ? n : null;
}

interface Rendition { url?: unknown; width?: unknown; height?: unknown }

/** Turns one GIPHY object into a [GifItem]; null when anything is missing or the host is not on the allow-list. */
export function toGifItem(raw: unknown): GifItem | null {
  if (!raw || typeof raw !== 'object') return null;
  const o = raw as { id?: unknown; title?: unknown; images?: Record<string, Rendition> };
  if (typeof o.id !== 'string' || !/^[A-Za-z0-9]{3,40}$/.test(o.id)) return null;
  const full = o.images?.fixed_width;
  const small = o.images?.fixed_width_small ?? o.images?.fixed_width;
  if (!full || !small) return null;
  if (!isAllowedGifUrl(full.url) || !isAllowedGifUrl(small.url)) return null;
  const width = positiveInt(full.width);
  const height = positiveInt(full.height);
  if (!width || !height) return null;
  const title = typeof o.title === 'string' ? o.title.replace(/\s+/g, ' ').trim().slice(0, 100) : '';
  return { id: o.id, title, previewUrl: small.url as string, url: full.url as string, width, height };
}

export function toGifPage(body: unknown, offset: number): GifPage {
  const b = (body ?? {}) as { data?: unknown; pagination?: { total_count?: unknown; count?: unknown } };
  const rows = Array.isArray(b.data) ? b.data : [];
  const items = rows.map(toGifItem).filter((x): x is GifItem => x !== null);
  const count = typeof b.pagination?.count === 'number' ? b.pagination.count : rows.length;
  const total = typeof b.pagination?.total_count === 'number' ? b.pagination.total_count : 0;
  const nextOffset = offset + count;
  const next = count > 0 && nextOffset < total && nextOffset <= MAX_OFFSET ? String(nextOffset) : null;
  return { items, next };
}

/** GIPHY v1. [apiKey] goes in the query string because that is how GIPHY takes it; the URL is never logged. */
export function createGiphyProvider(apiKey: string, fetcher: GifFetcher): GifProvider {
  async function get(path: string, params: Record<string, string>, offset: number): Promise<GifPage> {
    const qs = new URLSearchParams({ api_key: apiKey, limit: String(PAGE_SIZE), offset: String(offset), rating: GIF_RATING, ...params });
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), REQUEST_TIMEOUT_MS);
    try {
      const res = await fetcher(`https://api.giphy.com/v1/${path}?${qs.toString()}`, { signal: ctl.signal });
      if (res.status === 401 || res.status === 403) throw new HttpsError('failed-precondition', 'gifs-not-configured');
      if (res.status < 200 || res.status >= 300) throw new HttpsError('unavailable', 'gifs-unavailable');
      return toGifPage(await res.json(), offset);
    } catch (err) {
      if (err instanceof HttpsError) throw err;
      throw new HttpsError('unavailable', 'gifs-unavailable');
    } finally {
      clearTimeout(timer);
    }
  }
  const dir = (kind: GifKind) => (kind === 'sticker' ? 'stickers' : 'gifs');
  return {
    id: 'giphy',
    search: (query, kind, cursor) => get(`${dir(kind)}/search`, { q: query, lang: 'en' }, parseCursor(cursor)),
    trending: (kind, cursor) => get(`${dir(kind)}/trending`, {}, parseCursor(cursor)),
  };
}

/** Wraps a provider with a short in-memory cache (per function instance), so popular searches cost one call a minute. */
export function withCache(provider: GifProvider, nowMs: () => number = Date.now): GifProvider {
  const cache = new Map<string, { at: number; page: GifPage }>();
  async function cached(key: string, load: () => Promise<GifPage>): Promise<GifPage> {
    const hit = cache.get(key);
    if (hit && nowMs() - hit.at < CACHE_TTL_MS) return hit.page;
    const page = await load();
    if (cache.size >= CACHE_MAX_ENTRIES) cache.delete(cache.keys().next().value as string);
    cache.set(key, { at: nowMs(), page });
    return page;
  }
  return {
    id: provider.id,
    search: (q, kind, cursor) => cached(`s|${kind}|${q.toLowerCase()}|${cursor ?? ''}`, () => provider.search(q, kind, cursor)),
    trending: (kind, cursor) => cached(`t|${kind}|${cursor ?? ''}`, () => provider.trending(kind, cursor)),
  };
}

let shared: GifProvider | null = null;
let sharedKey = '';

/** The real provider, or null while the secret is not set up. Call only inside a function that binds [GIF_SECRETS]. */
export function defaultGifProvider(): GifProvider | null {
  let key: string | null = null;
  try {
    key = readGifKey(GIPHY_API_KEY.value());
  } catch {
    key = null;
  }
  if (!key) return null;
  if (!shared || sharedKey !== key) {
    shared = withCache(createGiphyProvider(key, (url, init) => fetch(url, init)));
    sharedKey = key;
  }
  return shared;
}

export function requireGifProvider(provider: GifProvider | null): GifProvider {
  if (!provider) throw new HttpsError('failed-precondition', 'gifs-not-configured');
  return provider;
}
