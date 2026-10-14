/**
 * The business details shown in every legal document, in one place. Every {{TOKEN}} in
 * src/content/legal/*.md is replaced from here at build time.
 *
 * Values marked "TO CONFIRM" are waiting for the owner (see docs/OWNER_SETUP.md, Step 9); the build
 * prints a warning while any remain, so the site is never published with them by accident.
 */
export const LEGAL_DETAILS = {
  OPERATOR: 'Arlo Technologies',
  ADDRESS: 'H No: 11, 13-746, Road No. 5, Dwarka Nagar, Kothapet, Hyderabad, Telangana 500102',
  GRIEVANCE_NAME: 'Durgam Vincent',
  GRIEVANCE_EMAIL: 'vincent@arlotechnologies.in',
  CONTACT_EMAIL: 'samvad@arlotechnologies.in',
  CHILD_SAFETY_EMAIL: 'contact@arlotechnologies.in',
  LEGAL_EMAIL: 'contact@arlotechnologies.in',
  SECURITY_EMAIL: 'contact@arlotechnologies.in',
} as const;

export type LegalToken = keyof typeof LEGAL_DETAILS;

/** Whether every detail has been supplied (no placeholder left). */
export const legalDetailsComplete = (): boolean =>
  Object.values(LEGAL_DETAILS).every((v) => !v.startsWith('TO CONFIRM') && !v.endsWith('samvad.example'));

export const LEGAL_GROUPS = {
  privacy: { title: 'Your privacy', description: 'What we collect, where it lives, and how to remove it.' },
  using: { title: 'Using Samvad', description: 'The agreement, the rules, and respecting creators.' },
  safety: { title: 'Safety', description: 'Protecting children and resolving complaints.' },
  authorities: { title: 'Authorities and researchers', description: 'Lawful requests and security reports.' },
} as const;

export type LegalGroup = keyof typeof LEGAL_GROUPS;
