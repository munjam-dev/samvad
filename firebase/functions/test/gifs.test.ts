import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { DAILY_SEARCHES, handleSearchGifs, handleTrendingGifs } from '../src/gifs';
import {
  createGiphyProvider, isAllowedGifUrl, normalizeQuery, readGifKey, withCache, toGifItem,
  type GifFetcher, type GifProvider,
} from '../src/lib/gifs';
import { previewOf } from '../src/onMessageCreated';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => `${e.code}:${e.message}`);

function row(id: string, over: Record<string, unknown> = {}) {
  return {
    id,
    title: `Title ${id}`,
    images: {
      fixed_width: { url: `https://media2.giphy.com/media/${id}/200w.gif?cid=1`, width: '200', height: '150' },
      fixed_width_small: { url: `https://media2.giphy.com/media/${id}/100w.gif?cid=1`, width: '100', height: '75' },
    },
    ...over,
  };
}

/** A fake fetcher: records URLs and answers with [body]. */
function fakeFetcher(body: unknown, status = 200) {
  const urls: string[] = [];
  const fetcher: GifFetcher = async (url) => {
    urls.push(url);
    return { status, json: async () => body };
  };
  return { fetcher, urls };
}

const page = (rows: unknown[], total = 100, offset = 0) => ({ data: rows, pagination: { total_count: total, count: rows.length, offset } });
const provider = (f: GifFetcher): GifProvider => createGiphyProvider('KEY123', f);

const search = (uid: string, data: unknown, p: GifProvider | null) => handleSearchGifs(phoneRequest(uid, { data }), deps, p);
const trending = (uid: string, data: unknown, p: GifProvider | null) => handleTrendingGifs(phoneRequest(uid, { data }), deps, p);

beforeEach(async () => {
  await resetEmulators();
});

describe('gif search', () => {
  it('returns only what the app needs, asks for a safe rating, and gives a next cursor', async () => {
    const { fetcher, urls } = fakeFetcher(page([row('abc1'), row('abc2')], 100));
    const r = await search('asha', { query: '  happy   dance ', kind: 'gif' }, provider(fetcher));
    expect(r.items).toEqual([
      { id: 'abc1', title: 'Title abc1', previewUrl: 'https://media2.giphy.com/media/abc1/100w.gif?cid=1', url: 'https://media2.giphy.com/media/abc1/200w.gif?cid=1', width: 200, height: 150 },
      expect.objectContaining({ id: 'abc2' }),
    ]);
    expect(Object.keys(r.items[0]).sort()).toEqual(['height', 'id', 'previewUrl', 'title', 'url', 'width']);
    expect(r.next).toBe('2');
    const u = new URL(urls[0]);
    expect(u.pathname).toBe('/v1/gifs/search');
    expect(u.searchParams.get('q')).toBe('happy dance');
    expect(u.searchParams.get('rating')).toBe('g');
    expect(u.searchParams.get('api_key')).toBe('KEY123');
  });

  it('uses the stickers endpoint and the cursor as the offset; no next cursor at the end', async () => {
    const { fetcher, urls } = fakeFetcher(page([row('abc1')], 25, 24));
    const r = await search('asha', { query: 'cat', kind: 'sticker', cursor: '24' }, provider(fetcher));
    const u = new URL(urls[0]);
    expect(u.pathname).toBe('/v1/stickers/search');
    expect(u.searchParams.get('offset')).toBe('24');
    expect(r.next).toBeNull();
  });

  it('drops results whose host is not on the allow-list or whose data is malformed', async () => {
    const bad = row('bad1', { images: { fixed_width: { url: 'https://evil.example.com/a.gif', width: '200', height: '100' }, fixed_width_small: { url: 'https://media2.giphy.com/x.gif', width: '1', height: '1' } } });
    const http = row('bad2', { images: { fixed_width: { url: 'http://media2.giphy.com/a.gif', width: '200', height: '100' }, fixed_width_small: { url: 'https://media2.giphy.com/x.gif', width: '1', height: '1' } } });
    const lookalike = row('bad3', { images: { fixed_width: { url: 'https://media2.giphy.com.evil.com/a.gif', width: '200', height: '100' }, fixed_width_small: { url: 'https://media2.giphy.com/x.gif', width: '1', height: '1' } } });
    const noSize = row('bad4', { images: { fixed_width: { url: 'https://media2.giphy.com/a.gif', width: '0', height: '100' }, fixed_width_small: { url: 'https://media2.giphy.com/x.gif', width: '1', height: '1' } } });
    const { fetcher } = fakeFetcher(page([bad, http, lookalike, noSize, row('ok1'), null, 'x', {}]));
    const r = await search('asha', { query: 'x', kind: 'gif' }, provider(fetcher));
    expect(r.items.map((i) => i.id)).toEqual(['ok1']);
  });

  it('allow-list helper accepts only the provider hosts over https', () => {
    expect(isAllowedGifUrl('https://media0.giphy.com/media/a/b.gif')).toBe(true);
    expect(isAllowedGifUrl('https://i.giphy.com/a.gif')).toBe(true);
    expect(isAllowedGifUrl('https://media.giphy.com/a.gif')).toBe(true);
    expect(isAllowedGifUrl('https://giphy.com/a.gif')).toBe(false);
    expect(isAllowedGifUrl('https://u:p@media1.giphy.com/a.gif')).toBe(false);
    expect(isAllowedGifUrl('https://media1.giphy.com:444/a.gif')).toBe(false);
    expect(isAllowedGifUrl(42)).toBe(false);
    expect(toGifItem({ id: 'a/b', images: {} })).toBeNull();
  });

  it('is not configured when there is no provider; the placeholder and empty key mean not configured', async () => {
    expect(await codeOf(search('asha', { query: 'x', kind: 'gif' }, null))).toBe('failed-precondition:gifs-not-configured');
    expect(await codeOf(trending('asha', { kind: 'gif' }, null))).toBe('failed-precondition:gifs-not-configured');
    expect(readGifKey('not-configured')).toBeNull();
    expect(readGifKey('  ')).toBeNull();
    expect(readGifKey(undefined)).toBeNull();
    expect(readGifKey(' abc ')).toBe('abc');
  });

  it('rejects empty, blank, too long and non-text queries, bad kinds and bad cursors', async () => {
    const { fetcher, urls } = fakeFetcher(page([]));
    const p = provider(fetcher);
    expect(await codeOf(search('asha', { query: '', kind: 'gif' }, p))).toBe('invalid-argument:query-empty');
    expect(await codeOf(search('asha', { query: '  \n\t ', kind: 'gif' }, p))).toBe('invalid-argument:query-empty');
    expect(await codeOf(search('asha', { query: 5, kind: 'gif' }, p))).toBe('invalid-argument:query-empty');
    expect(await codeOf(search('asha', { query: 'x'.repeat(51), kind: 'gif' }, p))).toBe('invalid-argument:query-too-long');
    expect(await codeOf(search('asha', { query: 'ok', kind: 'video' }, p))).toBe('invalid-argument:bad-kind');
    expect(await codeOf(search('asha', { query: 'ok', kind: 'gif', cursor: '-1' }, p))).toBe('invalid-argument:bad-cursor');
    expect(await codeOf(search('asha', { query: 'ok', kind: 'gif', cursor: '5000' }, p))).toBe('invalid-argument:bad-cursor');
    expect(await codeOf(trending('asha', { kind: 'gif', cursor: 'abc' }, p))).toBe('invalid-argument:bad-cursor');
    expect(urls).toHaveLength(0);
    expect(normalizeQuery('a\u0000b')).toBe('a b');
    expect(normalizeQuery('x'.repeat(50))).toHaveLength(50);
  });

  it('requires phone sign-in', async () => {
    const { fetcher } = fakeFetcher(page([]));
    const noAuth = { data: { query: 'x', kind: 'gif' } } as any;
    expect(await codeOf(handleSearchGifs(noAuth, deps, provider(fetcher)))).toMatch(/^unauthenticated/);
    expect(await codeOf(handleSearchGifs(phoneRequest('asha', { provider: 'password', data: { query: 'x', kind: 'gif' } }), deps, provider(fetcher)))).toMatch(/^permission-denied/);
  });

  it('stops at the daily limit', async () => {
    const { fetcher } = fakeFetcher(page([row('abc1')]));
    await deps.db.doc('rateLimits/asha').set({ gifSearchWindowStart: Timestamp.fromMillis(deps.nowMs()), gifSearchCount: DAILY_SEARCHES });
    expect(await codeOf(search('asha', { query: 'x', kind: 'gif' }, provider(fetcher)))).toMatch(/^resource-exhausted/);
    // Another account is not affected, and trending has its own budget.
    expect(await codeOf(search('ravi', { query: 'x', kind: 'gif' }, provider(fetcher)))).toBe('ok');
    expect(await codeOf(trending('asha', { kind: 'gif' }, provider(fetcher)))).toBe('ok');
  });

  it('turns provider failures into a friendly unavailable error, and a rejected key into not configured', async () => {
    expect(await codeOf(search('asha', { query: 'x', kind: 'gif' }, provider(fakeFetcher({}, 500).fetcher)))).toBe('unavailable:gifs-unavailable');
    expect(await codeOf(search('asha', { query: 'x', kind: 'gif' }, provider(fakeFetcher({}, 429).fetcher)))).toBe('unavailable:gifs-unavailable');
    expect(await codeOf(search('asha', { query: 'x', kind: 'gif' }, provider(fakeFetcher({}, 403).fetcher)))).toBe('failed-precondition:gifs-not-configured');
    const throwing: GifFetcher = async () => { throw new Error('network down'); };
    expect(await codeOf(search('asha', { query: 'x', kind: 'gif' }, provider(throwing)))).toBe('unavailable:gifs-unavailable');
    const badJson: GifFetcher = async () => ({ status: 200, json: async () => { throw new Error('bad json'); } });
    expect(await codeOf(search('asha', { query: 'x', kind: 'gif' }, provider(badJson)))).toBe('unavailable:gifs-unavailable');
  });

  it('caches identical requests for a short time', async () => {
    let now = 1_000_000;
    const { fetcher, urls } = fakeFetcher(page([row('abc1')]));
    const p = withCache(provider(fetcher), () => now);
    await p.search('Cat', 'gif', null);
    await p.search('cat', 'gif', null);
    expect(urls).toHaveLength(1);
    await p.search('cat', 'sticker', null);
    await p.trending('gif', null);
    await p.trending('gif', null);
    expect(urls).toHaveLength(3);
    now += 61_000;
    await p.search('cat', 'gif', null);
    expect(urls).toHaveLength(4);
  });
});

describe('gif trending', () => {
  it('returns the trending page', async () => {
    const { fetcher, urls } = fakeFetcher(page([row('abc1')], 1));
    const r = await trending('asha', { kind: 'sticker' }, provider(fetcher));
    expect(new URL(urls[0]).pathname).toBe('/v1/stickers/trending');
    expect(r.items).toHaveLength(1);
    expect(r.next).toBeNull();
  });
});

describe('message previews', () => {
  it('labels GIFs and stickers', () => {
    expect(previewOf({ kind: 'gif', text: '' } as any)).toBe('GIF');
    expect(previewOf({ kind: 'sticker', text: '' } as any)).toBe('Sticker');
    expect(previewOf({ kind: 'gif', text: 'so true' } as any)).toBe('GIF · so true');
  });
});
