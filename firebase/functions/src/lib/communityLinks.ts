import type { Timestamp } from 'firebase-admin/firestore';

/** One group linked into a community, as stored on the community document (members of the community cannot read the group itself). */
export interface LinkedGroup {
  cid: string;
  name: string;
  linkedBy: string;
  linkedAt: Timestamp;
}

export function withoutGroup(groups: LinkedGroup[], cid: string): LinkedGroup[] {
  return groups.filter((g) => g.cid !== cid);
}

export function renameGroup(groups: LinkedGroup[], cid: string, name: string): LinkedGroup[] {
  return groups.map((g) => (g.cid === cid ? { ...g, name } : g));
}

/** The two fields that always change together when the link list changes. */
export function linkFields(groups: LinkedGroup[]): { groups: LinkedGroup[]; linkedGroupIds: string[] } {
  return { groups, linkedGroupIds: groups.map((g) => g.cid) };
}

/** Reads the stored link list defensively (a missing or malformed field is an empty list). */
export function readGroups(raw: unknown): LinkedGroup[] {
  if (!Array.isArray(raw)) return [];
  return raw.filter(
    (g): g is LinkedGroup => !!g && typeof g.cid === 'string' && typeof g.name === 'string' && typeof g.linkedBy === 'string',
  );
}
