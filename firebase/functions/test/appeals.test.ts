import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { DAILY_APPEALS, handleSubmitAppeal, MAX_MESSAGE, MIN_MESSAGE, parseAppeal } from '../src/appeals';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { callCallable, resetEmulators, signInWithPhone } from '../../test-support/auth';

const deps = testDeps();
const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);
const submit = (uid: string, data: unknown) => handleSubmitAppeal(phoneRequest(uid, { data }), deps);
const good = { kind: 'report_decision', message: 'I think this decision was a mistake.' };

beforeEach(resetEmulators);

describe('parseAppeal', () => {
  it('accepts the three kinds and trims the message', () => {
    for (const kind of ['report_decision', 'account_restriction', 'other']) {
      expect(parseAppeal({ kind, message: '  0123456789  ' })).toEqual({ kind, message: '0123456789' });
    }
  });

  it('enforces 10 to 1000 characters after trimming', () => {
    expect(() => parseAppeal({ kind: 'other', message: ' '.repeat(5) + 'a'.repeat(MIN_MESSAGE - 1) })).toThrow();
    expect(() => parseAppeal({ kind: 'other', message: 'a'.repeat(MIN_MESSAGE) })).not.toThrow();
    expect(() => parseAppeal({ kind: 'other', message: 'a'.repeat(MAX_MESSAGE) })).not.toThrow();
    expect(() => parseAppeal({ kind: 'other', message: 'a'.repeat(MAX_MESSAGE + 1) })).toThrow();
  });

  it('rejects unknown kinds and wrong types', () => {
    for (const data of [null, {}, { kind: 'spam', message: 'long enough text' }, { kind: 5, message: 'long enough text' }, { kind: 'other', message: 12345678901 }, { kind: 'other' }]) {
      expect(() => parseAppeal(data)).toThrow();
    }
  });
});

describe('submitAppeal', () => {
  it('writes a server-owned appeal with the caller uid, status open and a server timestamp', async () => {
    const { id } = await submit('alice', good);
    const doc = (await deps.db.doc(`appeals/${id}`).get()).data()!;
    expect(doc).toMatchObject({ uid: 'alice', kind: 'report_decision', message: good.message, status: 'open' });
    expect(doc.createdAt).toBeInstanceOf(Timestamp);
    expect(Object.keys(doc).sort()).toEqual(['createdAt', 'kind', 'message', 'status', 'uid']);
  });

  it('ignores a uid or status sent by the client', async () => {
    const { id } = await submit('alice', { ...good, uid: 'bob', status: 'granted' });
    const doc = (await deps.db.doc(`appeals/${id}`).get()).data()!;
    expect(doc.uid).toBe('alice');
    expect(doc.status).toBe('open');
  });

  it('allows three a day, then asks the person to wait; another account is not affected', async () => {
    for (let i = 0; i < DAILY_APPEALS; i++) await submit('alice', good);
    expect(await codeOf(submit('alice', good))).toBe('resource-exhausted');
    expect((await deps.db.collection('appeals').where('uid', '==', 'alice').get()).size).toBe(DAILY_APPEALS);
    expect(await codeOf(submit('bob', good))).toBe('ok');
    const tomorrow = testDeps({ nowMs: () => Date.now() + 24 * 60 * 60 * 1000 + 1000 });
    expect(await codeOf(handleSubmitAppeal(phoneRequest('alice', { data: good }), tomorrow))).toBe('ok');
  });

  it('a rejected appeal does not use up the daily allowance', async () => {
    for (let i = 0; i < 5; i++) expect(await codeOf(submit('alice', { kind: 'other', message: 'short' }))).toBe('invalid-argument');
    for (let i = 0; i < DAILY_APPEALS; i++) await submit('alice', good);
  });

  it('requires a phone sign-in', async () => {
    expect(await codeOf(handleSubmitAppeal({ data: good } as any, deps))).toBe('unauthenticated');
    expect(await codeOf(handleSubmitAppeal(phoneRequest('alice', { provider: 'anonymous', data: good }), deps))).toBe('permission-denied');
    expect((await deps.db.collection('appeals').get()).size).toBe(0);
  });
});

describe('submitAppeal over HTTP', () => {
  it('rejects an unauthenticated call', async () => {
    const res = await callCallable('submitAppeal', null, good);
    expect(res.body?.error?.status).toBe('UNAUTHENTICATED');
  });

  it('stores an appeal for the signed-in caller', async () => {
    const { uid, idToken } = await signInWithPhone('+919999900072');
    const res = await callCallable('submitAppeal', idToken, good);
    const id = res.body.result.id as string;
    expect((await deps.db.doc(`appeals/${id}`).get()).get('uid')).toBe(uid);
  });

  it('answers invalid-argument for a message that is too short', async () => {
    const { idToken } = await signInWithPhone('+919999900073');
    const res = await callCallable('submitAppeal', idToken, { kind: 'other', message: 'no' });
    expect(res.body?.error?.status).toBe('INVALID_ARGUMENT');
  });
});
