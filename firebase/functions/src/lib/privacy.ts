import type { DocumentData, Firestore, Transaction } from 'firebase-admin/firestore';

/**
 * Phase 3 privacy settings, stored at users/{uid}/settings/privacy (the owner writes them, under the rules):
 * who sees "About" and the profile photo, who may add the person to a group, and whether the phone number
 * can be used to find them. The last-seen setting lives in the Realtime Database (privacy/{uid}/lastSeen).
 */
export type Visibility = 'everyone' | 'partners' | 'nobody';

export const VISIBILITIES: readonly Visibility[] = ['everyone', 'partners', 'nobody'];

export interface PrivacySettings {
  aboutVisibility: Visibility;
  photoVisibility: Visibility;
  groupAdds: Visibility;
  phoneDiscovery: boolean;
}

export const DEFAULT_PRIVACY: PrivacySettings = {
  aboutVisibility: 'everyone',
  photoVisibility: 'everyone',
  groupAdds: 'everyone',
  phoneDiscovery: true,
};

const asVisibility = (v: unknown): Visibility => (VISIBILITIES.includes(v as Visibility) ? (v as Visibility) : 'everyone');

/** Settings from a document; anything missing or unknown falls back to the open default. */
export function parsePrivacy(data: DocumentData | undefined): PrivacySettings {
  return {
    aboutVisibility: asVisibility(data?.aboutVisibility),
    photoVisibility: asVisibility(data?.photoVisibility),
    groupAdds: asVisibility(data?.groupAdds),
    phoneDiscovery: data?.phoneDiscovery === false ? false : true,
  };
}

export const privacyPath = (uid: string) => `users/${uid}/settings/privacy`;

/** The id of the direct conversation between two people (sorted, joined by an underscore). */
export function directCid(a: string, b: string): string {
  return a < b ? `${a}_${b}` : `${b}_${a}`;
}

/**
 * "People I chat with" for [owner]: the direct conversation exists and the owner started it or has sent a
 * message in it (conversations/{cid}.engagedBy). Someone who merely creates a chat with the owner is not one.
 */
export function isEngagedPartner(conv: DocumentData | undefined, owner: string): boolean {
  return Array.isArray(conv?.engagedBy) && (conv!.engagedBy as unknown[]).includes(owner);
}

/** Reads the settings of several people inside a transaction (missing documents give the defaults). */
export async function loadPrivacyTx(tx: Transaction, db: Firestore, uids: string[]): Promise<Map<string, PrivacySettings>> {
  const snaps = await Promise.all(uids.map((u) => tx.get(db.doc(privacyPath(u)))));
  return new Map(uids.map((u, i) => [u, parsePrivacy(snaps[i].data())]));
}
