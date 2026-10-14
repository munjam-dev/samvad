import type { FormEvent } from 'react';
import { LEGAL_DETAILS } from '@/config/legal';

/**
 * The site is static, so contact forms open the visitor's mail app with the message filled in.
 * Nothing is sent to a server, and nothing is stored.
 */
export function submitMailto(e: FormEvent<HTMLFormElement>, subject: string, to: string = LEGAL_DETAILS.CONTACT_EMAIL) {
  e.preventDefault();
  const data = new FormData(e.currentTarget);
  const lines: string[] = [];
  data.forEach((value, key) => {
    if (typeof value === 'string' && value.trim()) lines.push(`${key}: ${value.trim()}`);
  });
  window.location.href = `mailto:${to}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(lines.join('\n'))}`;
}
