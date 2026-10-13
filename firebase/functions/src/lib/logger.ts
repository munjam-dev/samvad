import { randomUUID } from 'node:crypto';
import * as logger from 'firebase-functions/logger';

const ALLOWED_FIELDS = ['correlationId', 'count', 'outcome'] as const;

export function newCorrelationId(): string {
  return randomUUID();
}

/** Structured, PII-free event log: every field outside the allow-list is dropped. */
export function logEvent(event: string, fields: Record<string, string | number | boolean> = {}): void {
  const safe: Record<string, string | number | boolean> = {};
  for (const key of ALLOWED_FIELDS) {
    if (key in fields) safe[key] = fields[key];
  }
  logger.info(event, { event, ...safe });
}
