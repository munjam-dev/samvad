import fs from 'fs';
import path from 'path';
import GithubSlugger from 'github-slugger';
import matter from 'gray-matter';
import { LEGAL_DETAILS, legalDetailsComplete, type LegalGroup } from '@/config/legal';

const DIR = path.join(process.cwd(), 'src/content/legal');

export interface LegalDocMeta {
  slug: string;
  title: string;
  summary: string;
  updated: string;
  order: number;
  group: LegalGroup;
}

export interface LegalDoc extends LegalDocMeta {
  content: string;
  toc: { id: string; title: string; level: 2 | 3 }[];
}

let warned = false;

/** Replaces {{TOKEN}}s with the business details; unknown tokens fail the build. */
function fillTokens(raw: string, slug: string): string {
  if (!warned && !legalDetailsComplete()) {
    warned = true;
    console.warn('[legal] Business details are still placeholders in src/config/legal.ts. Do not publish yet.');
  }
  return raw.replace(/\{\{([A-Z_]+)\}\}/g, (_, key: string) => {
    if (!(key in LEGAL_DETAILS)) throw new Error(`[legal] Unknown token {{${key}}} in ${slug}.md`);
    return LEGAL_DETAILS[key as keyof typeof LEGAL_DETAILS];
  });
}

/** Same ids rehype-slug gives the rendered headings (both use github-slugger, in document order). */
function tableOfContents(markdown: string): LegalDoc['toc'] {
  const slugger = new GithubSlugger();
  const toc: LegalDoc['toc'] = [];
  for (const line of markdown.split('\n')) {
    const m = /^(#{1,6})\s+(.+?)\s*$/.exec(line);
    if (!m) continue;
    const text = m[2].replace(/[*_`]/g, '');
    const id = slugger.slug(text);
    const level = m[1].length;
    if (level === 2 || level === 3) toc.push({ id, title: text, level });
  }
  return toc;
}

export function legalSlugs(): string[] {
  return fs
    .readdirSync(DIR)
    .filter((f) => f.endsWith('.md'))
    .map((f) => f.replace(/\.md$/, ''));
}

export function getLegalDoc(slug: string): LegalDoc {
  const file = path.join(DIR, `${slug}.md`);
  const parsed = matter(fs.readFileSync(file, 'utf8'));
  const content = fillTokens(parsed.content, slug);
  const d = parsed.data as Partial<LegalDocMeta>;
  if (!d.title || !d.summary || !d.updated || d.order == null || !d.group) {
    throw new Error(`[legal] ${slug}.md is missing front matter (title, summary, updated, order, group)`);
  }
  return {
    slug,
    title: d.title,
    summary: fillTokens(d.summary, slug),
    // YAML reads 2026-10-14 as a Date; keep the plain day either way.
    updated: ((d.updated as unknown) instanceof Date ? (d.updated as unknown as Date).toISOString() : String(d.updated)).slice(0, 10),
    order: Number(d.order),
    group: d.group,
    content,
    toc: tableOfContents(content),
  };
}

export function allLegalDocs(): LegalDocMeta[] {
  return legalSlugs()
    .map((slug): LegalDocMeta => {
      const d = getLegalDoc(slug);
      return { slug: d.slug, title: d.title, summary: d.summary, updated: d.updated, order: d.order, group: d.group };
    })
    .sort((a, b) => a.order - b.order);
}

/** "14 October 2026" — documents are Indian-English, so day-month-year. */
export function formatLegalDate(iso: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });
}
