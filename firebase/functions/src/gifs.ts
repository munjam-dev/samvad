import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth } from './lib/guards';
import { consumeDailyBudget } from './lib/budget';
import { logEvent, newCorrelationId } from './lib/logger';
import {
  GIF_SECRETS, defaultGifProvider, normalizeQuery, parseCursor, parseKind, requireGifProvider,
  type GifPage, type GifProvider,
} from './lib/gifs';

/** Per account and day. Scrolling loads a page per call, so these are generous but bounded. */
export const DAILY_SEARCHES = 300;
export const DAILY_TRENDING = 150;

type Req = CallableRequest<unknown>;

async function run<T>(event: string, work: () => Promise<T>): Promise<T> {
  const correlationId = newCorrelationId();
  try {
    const result = await work();
    logEvent(event, { correlationId, outcome: 'ok' });
    return result;
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent(event, { correlationId, outcome: err.code });
      throw err;
    }
    logEvent(event, { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
}

export async function searchGifsCore(
  deps: Deps, provider: GifProvider | null, uid: string, data: { query?: unknown; kind?: unknown; cursor?: unknown },
): Promise<GifPage> {
  const p = requireGifProvider(provider);
  const kind = parseKind(data.kind);
  const query = normalizeQuery(data.query);
  const cursor = parseCursor(data.cursor);
  await consumeDailyBudget(deps, uid, 'gifSearch', DAILY_SEARCHES);
  return p.search(query, kind, cursor === 0 ? null : String(cursor));
}

export async function trendingGifsCore(
  deps: Deps, provider: GifProvider | null, uid: string, data: { kind?: unknown; cursor?: unknown },
): Promise<GifPage> {
  const p = requireGifProvider(provider);
  const kind = parseKind(data.kind);
  const cursor = parseCursor(data.cursor);
  await consumeDailyBudget(deps, uid, 'gifTrending', DAILY_TRENDING);
  return p.trending(kind, cursor === 0 ? null : String(cursor));
}

export const handleSearchGifs = async (r: Req, deps: Deps = defaultDeps(), provider: GifProvider | null = defaultGifProvider()) => {
  const { uid } = requirePhoneAuth(r);
  return run('gifs_searched', () => searchGifsCore(deps, provider, uid, (r.data ?? {}) as Record<string, unknown>));
};

export const handleTrendingGifs = async (r: Req, deps: Deps = defaultDeps(), provider: GifProvider | null = defaultGifProvider()) => {
  const { uid } = requirePhoneAuth(r);
  return run('gifs_trending', () => trendingGifsCore(deps, provider, uid, (r.data ?? {}) as Record<string, unknown>));
};

const opts = () => ({ ...buildCallableOptions(process.env), secrets: GIF_SECRETS });

export const searchGifs = onCall(opts(), (r) => handleSearchGifs(r));
export const trendingGifs = onCall(opts(), (r) => handleTrendingGifs(r));
