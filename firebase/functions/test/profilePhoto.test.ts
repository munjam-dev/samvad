import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { bucketName } from '../src/deps';
import { JpegError, stripJpegMetadata } from '../src/lib/jpeg';
import { DAILY_PHOTO_CHANGES, handleSetProfilePhoto, parsePhoto } from '../src/setProfilePhoto';
import { deleteAccountCore } from '../src/deleteAccount';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const UID = 'nila';
const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.message === 'not-a-jpeg' || e.message === 'photo-too-large' ? e.message : e.code);

const seg = (marker: number, body: Buffer) => {
  const head = Buffer.alloc(4);
  head[0] = 0xff;
  head[1] = marker;
  head.writeUInt16BE(body.length + 2, 2);
  return Buffer.concat([head, body]);
};
/** A structurally valid JPEG with JFIF, an EXIF block holding "GPS", a comment, a frame and scan data. */
function jpeg(width = 512, height = 512, withMetadata = true): Buffer {
  const sof = Buffer.from([8, height >> 8, height & 0xff, width >> 8, width & 0xff, 1, 1, 0x11, 0]);
  const parts = [
    Buffer.from([0xff, 0xd8]),
    seg(0xe0, Buffer.from('JFIF\0\x01\x01\0\0\x01\0\x01\0\0', 'binary')),
    ...(withMetadata ? [seg(0xe1, Buffer.from('Exif\0\0GPS 12.97N 77.59E', 'binary')), seg(0xfe, Buffer.from('taken at home'))] : []),
    seg(0xdb, Buffer.alloc(65, 1)),
    seg(0xc0, sof),
    seg(0xda, Buffer.from([1, 1, 0, 0, 0x3f, 0])),
    Buffer.from([0x12, 0x34, 0xff, 0x00, 0x56]),
    Buffer.from([0xff, 0xd9]),
  ];
  return Buffer.concat(parts);
}
const b64 = (b: Buffer) => b.toString('base64');
const read = async (path: string) => (await getStorage().bucket(bucketName()).file(path).download())[0];
const exists = async (path: string) => (await getStorage().bucket(bucketName()).file(path).exists())[0];
const profile = async () => (await deps.db.doc(`profiles/${UID}/private/photo`).get()).data() ?? { photoUrl: null };

beforeEach(async () => {
  await resetEmulators();
  await deps.db.doc(`profiles/${UID}`).set({ displayName: 'Nila', updatedAt: Timestamp.now() });
});

describe('JPEG handling', () => {
  it('drops EXIF and comments, keeps JFIF and image data, and reads the frame size', () => {
    const clean = stripJpegMetadata(jpeg(640, 480));
    expect([clean.width, clean.height]).toEqual([640, 480]);
    expect(clean.bytes.includes(Buffer.from('GPS'))).toBe(false);
    expect(clean.bytes.includes(Buffer.from('taken at home'))).toBe(false);
    expect(clean.bytes.includes(Buffer.from('JFIF'))).toBe(true);
    expect(clean.bytes.equals(stripJpegMetadata(jpeg(640, 480, false)).bytes)).toBe(true);
  });

  it('rejects anything that is not a whole JPEG', () => {
    const png = Buffer.from('89504e470d0a1a0a0000', 'hex');
    for (const bad of [png, Buffer.alloc(0), jpeg().subarray(0, 40), Buffer.from([0xff, 0xd8, 0xff, 0xd9])]) {
      expect(() => stripJpegMetadata(bad)).toThrow(JpegError);
    }
  });

  it('parses only base64 within the size cap, or null to remove', () => {
    expect(parsePhoto({ jpegBase64: null })).toBeNull();
    expect(() => parsePhoto({})).toThrow();
    expect(() => parsePhoto({ jpegBase64: 'not base64!' })).toThrow();
    expect(() => parsePhoto({ jpegBase64: b64(Buffer.alloc(600 * 1024)) })).toThrow('photo-too-large');
  });
});

describe('setProfilePhoto', () => {
  it('stores a cleaned copy, points the profile at it, and replaces the old file', async () => {
    const first = await handleSetProfilePhoto(phoneRequest(UID, { data: { jpegBase64: b64(jpeg()) } }), deps);
    expect(first.photoUrl).toMatch(new RegExp(`^avatars/${UID}/[0-9a-f-]{36}[.]jpg$`));
    expect((await profile()).photoUrl).toBe(first.photoUrl);
    expect((await read(first.photoUrl!)).includes(Buffer.from('GPS'))).toBe(false);

    const second = await handleSetProfilePhoto(phoneRequest(UID, { data: { jpegBase64: b64(jpeg()) } }), deps);
    expect(second.photoUrl).not.toBe(first.photoUrl);
    expect(await exists(first.photoUrl!)).toBe(false);

    const removed = await handleSetProfilePhoto(phoneRequest(UID, { data: { jpegBase64: null } }), deps);
    expect(removed.photoUrl).toBeNull();
    expect((await profile()).photoUrl).toBeNull();
    expect(await exists(second.photoUrl!)).toBe(false);
  });

  it('rejects non-JPEG and oversized images, and callers without a profile', async () => {
    const png = Buffer.from('89504e470d0a1a0a0000000d49484452', 'hex');
    expect(await codeOf(handleSetProfilePhoto(phoneRequest(UID, { data: { jpegBase64: b64(png) } }), deps))).toBe('not-a-jpeg');
    expect(await codeOf(handleSetProfilePhoto(phoneRequest(UID, { data: { jpegBase64: b64(jpeg(4000, 3000)) } }), deps))).toBe(
      'photo-too-large',
    );
    expect(await codeOf(handleSetProfilePhoto(phoneRequest('nobody', { data: { jpegBase64: b64(jpeg()) } }), deps))).toBe(
      'failed-precondition',
    );
    expect(await codeOf(handleSetProfilePhoto({ data: { jpegBase64: null } } as any, deps))).toBe('unauthenticated');
  });

  it('limits changes per day', async () => {
    await deps.db.doc(`rateLimits/${UID}`).set({ photoWindowStart: Timestamp.now(), photoCount: DAILY_PHOTO_CHANGES });
    expect(await codeOf(handleSetProfilePhoto(phoneRequest(UID, { data: { jpegBase64: null } }), deps))).toBe('resource-exhausted');
  });

  it('deleteAccount removes the photo files', async () => {
    await deps.auth.createUser({ uid: UID });
    const { photoUrl } = await handleSetProfilePhoto(phoneRequest(UID, { data: { jpegBase64: b64(jpeg()) } }), deps);
    await deleteAccountCore(deps, UID);
    expect(await exists(photoUrl!)).toBe(false);
  });
});
