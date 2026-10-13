import { randomUUID } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { previewOf } from '../src/onMessageCreated';
import { cleanupDeletedMedia } from '../src/onMessageUpdated';
import { bucketName } from '../src/deps';
import { testDeps } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const bucket = () => getStorage().bucket(bucketName());

beforeEach(resetEmulators);

describe('media previews', () => {
  const base = { senderId: 'a', createdAt: Timestamp.now() };
  it('labels media kinds and appends captions', () => {
    expect(previewOf({ ...base, kind: 'image', text: '' })).toBe('📷 Photo');
    expect(previewOf({ ...base, kind: 'image', text: 'Sunset' })).toBe('📷 Photo · Sunset');
    expect(previewOf({ ...base, kind: 'voice', text: '' })).toBe('🎤 Voice message');
    expect(previewOf({ ...base, kind: 'file', text: '', media: { name: 'Trip.pdf' } })).toBe('📄 Trip.pdf');
    expect(previewOf({ ...base, kind: 'text', text: 'hello' })).toBe('hello');
  });
});

describe('cleanupDeletedMedia', () => {
  it('deletes the stored file and the media field when a media message is deleted', async () => {
    const mid = randomUUID();
    const path = `conversations/alice_bob/${mid}/photo.jpg`;
    await bucket().file(path).save(Buffer.from('jpeg-bytes'), { contentType: 'image/jpeg' });
    await deps.db.doc(`conversations/alice_bob/messages/${mid}`).set({ senderId: 'alice', kind: 'image', deleted: true, text: '', media: { path } });
    const done = await cleanupDeletedMedia(deps, 'alice_bob', mid, { deleted: false, media: { path } }, { deleted: true, media: { path } });
    expect(done).toBe(true);
    const [exists] = await bucket().file(path).exists();
    expect(exists).toBe(false);
    expect((await deps.db.doc(`conversations/alice_bob/messages/${mid}`).get()).get('media')).toBeUndefined();
  });

  it('ignores updates that are not a first-time deletion of a media message', async () => {
    let calls = 0;
    const counting = testDeps({ deleteFiles: async () => { calls++; } });
    expect(await cleanupDeletedMedia(counting, 'c', 'm', { deleted: true }, { deleted: true, media: {} })).toBe(false);
    expect(await cleanupDeletedMedia(counting, 'c', 'm', { deleted: false }, { deleted: false, media: {} })).toBe(false);
    expect(await cleanupDeletedMedia(counting, 'c', 'm', { deleted: false }, { deleted: true })).toBe(false);
    expect(calls).toBe(0);
  });
});
