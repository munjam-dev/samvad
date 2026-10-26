#!/usr/bin/env node
/**
 * Link audit for the Samvad site, the docs, and the URLs the Android app opens.
 *
 *   node scripts/check-links.mjs             internal checks only (needs `npm run build` first, reads ./out)
 *   node scripts/check-links.mjs --external  also sends a HEAD request (GET fallback) to every external URL
 *
 * Checks:
 *   1. Every internal href/src in the built HTML (out/**.html) points to a page, file or #anchor that exists.
 *   2. Every redirect in firebase/firebase.json points to a page that exists in out/.
 *   3. Every relative link in README.md, NOTICE and docs/**.md points to a file that exists.
 *   4. Every https:// URL in the Android sources: URLs on the site's own host are resolved against out/ and the
 *      redirects; all others are listed and, with --external, requested.
 * Exit code 1 when an internal link is broken. External failures are reported but only fail the run with --strict.
 */
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const websiteDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = resolve(websiteDir, '..');
const outDir = join(websiteDir, 'out');
const SITE_HOSTS = new Set(['samvad-messenger.web.app', 'samvad-messenger.firebaseapp.com']);
const args = new Set(process.argv.slice(2));
const checkExternal = args.has('--external');
const strict = args.has('--strict');

const broken = [];
const externalUrls = new Map(); // url -> where first seen
const note = (kind, where, target, why) => broken.push({ kind, where, target, why });

function walk(dir, filter, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === '.git' || name === 'build' || name === '.next' || name === 'out') continue;
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, filter, out);
    else if (filter(p)) out.push(p);
  }
  return out;
}

if (!existsSync(outDir)) {
  console.error('website/out not found. Run `npm run build` first (next.config.ts uses output: "export").');
  process.exit(2);
}

// ---------- 1. Built site ----------
const htmlFiles = [];
(function collect(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) collect(p);
    else if (p.endsWith('.html')) htmlFiles.push(p);
  }
})(outDir);

const idCache = new Map();
function idsIn(file) {
  if (!idCache.has(file)) {
    const html = readFileSync(file, 'utf8');
    idCache.set(file, new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1])));
  }
  return idCache.get(file);
}

/** Resolve a site path ("/legal/privacy-policy") to a file inside out/, or null. */
function resolveSitePath(pathname) {
  let p = decodeURIComponent(pathname).replace(/\/+$/, '');
  const candidates = [p, `${p}.html`, `${p}/index.html`];
  if (p === '') candidates.unshift('/index.html');
  for (const c of candidates) {
    const f = join(outDir, c);
    if (existsSync(f) && statSync(f).isFile()) return f;
  }
  return null;
}

const redirects = new Map();
try {
  const fb = JSON.parse(readFileSync(join(repoRoot, 'firebase', 'firebase.json'), 'utf8'));
  for (const r of fb.hosting?.redirects ?? []) redirects.set(r.source, r.destination);
} catch (e) {
  note('config', 'firebase/firebase.json', '', `cannot read hosting redirects: ${e.message}`);
}

function checkSiteUrl(pathname, hash, where, kind) {
  const redirected = redirects.get(pathname.replace(/\/+$/, '') || '/');
  const target = redirected ?? pathname;
  const file = resolveSitePath(target);
  if (!file) {
    note(kind, where, pathname + (hash ? `#${hash}` : ''), redirected ? `redirects to ${redirected}, which does not exist` : 'no such page or file');
    return;
  }
  if (hash && file.endsWith('.html') && !idsIn(file).has(decodeURIComponent(hash))) {
    note(kind, where, `${pathname}#${hash}`, 'page exists but has no element with that id');
  }
}

for (const file of htmlFiles) {
  const html = readFileSync(file, 'utf8');
  const here = '/' + relative(outDir, file).split(sep).join('/').replace(/(^|\/)index\.html$/, '').replace(/\.html$/, '');
  const where = `out${here || '/'}`;
  for (const m of html.matchAll(/\s(?:href|src)="([^"]+)"/g)) {
    const raw = m[1].replace(/&amp;/g, '&');
    if (/^(mailto:|tel:|data:|javascript:|blob:)/.test(raw)) continue;
    if (/^https?:\/\//.test(raw)) {
      let u;
      try { u = new URL(raw); } catch { note('site', where, raw, 'malformed URL'); continue; }
      if (SITE_HOSTS.has(u.hostname)) checkSiteUrl(u.pathname, u.hash.slice(1), where, 'site');
      else if (!externalUrls.has(raw)) externalUrls.set(raw, where);
      continue;
    }
    if (raw.startsWith('//')) continue;
    let pathname;
    let hash = '';
    if (raw.startsWith('#')) {
      const f = resolveSitePath(here);
      if (f && raw.length > 1 && !idsIn(f).has(raw.slice(1))) note('site', where, raw, 'no element with that id on this page');
      continue;
    }
    const base = new URL(raw, `http://x${here}/`);
    pathname = base.pathname;
    hash = base.hash.slice(1);
    if (pathname.startsWith('/_next/') || pathname.startsWith('/__next')) {
      if (!existsSync(join(outDir, pathname))) note('site', where, pathname, 'asset missing from out/');
      continue;
    }
    checkSiteUrl(pathname, hash, where, 'site');
  }
}

// ---------- 2. Redirect destinations ----------
for (const [src, dest] of redirects) {
  if (/^https?:/.test(dest)) { externalUrls.set(dest, `firebase.json redirect ${src}`); continue; }
  if (!resolveSitePath(dest)) note('redirect', 'firebase/firebase.json', `${src} -> ${dest}`, 'destination page does not exist in out/');
}

// ---------- 3. Markdown links in docs ----------
const mdFiles = [
  ...['README.md', 'NOTICE'].map((f) => join(repoRoot, f)).filter(existsSync),
  ...walk(join(repoRoot, 'docs'), (p) => p.endsWith('.md')),
  ...walk(join(repoRoot, 'android-native'), (p) => p.endsWith('README.md')),
  ...walk(join(repoRoot, 'firebase'), (p) => p.endsWith('README.md')),
  ...walk(join(websiteDir, 'src', 'content'), (p) => p.endsWith('.md')),
];
for (const file of mdFiles) {
  const text = readFileSync(file, 'utf8');
  const where = relative(repoRoot, file).split(sep).join('/');
  const inContent = where.startsWith('website/src/content/');
  for (const m of text.matchAll(/\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)) {
    const raw = m[1];
    if (/^(mailto:|tel:)/.test(raw)) continue;
    if (/^https?:\/\//.test(raw)) {
      let u;
      try { u = new URL(raw); } catch { note('doc', where, raw, 'malformed URL'); continue; }
      if (SITE_HOSTS.has(u.hostname)) checkSiteUrl(u.pathname, u.hash.slice(1), where, 'doc');
      else if (!externalUrls.has(raw)) externalUrls.set(raw, where);
      continue;
    }
    if (raw.includes('{{')) continue; // legal tokens, replaced at build time
    if (raw.startsWith('#')) continue;
    if (inContent && raw.startsWith('/')) {
      const [pth, hash = ''] = raw.split('#');
      checkSiteUrl(pth, hash, where, 'doc');
      continue;
    }
    const [relPath] = raw.split('#');
    const target = resolve(dirname(file), decodeURIComponent(relPath));
    if (!existsSync(target)) note('doc', where, raw, 'file does not exist');
  }
}

// ---------- 4. URLs the Android app can open ----------
const appFiles = [
  ...walk(join(repoRoot, 'android-native', 'app', 'src', 'main'), (p) => /\.(kt|xml)$/.test(p)),
  ...walk(join(repoRoot, 'android-native', 'core', 'src', 'main'), (p) => p.endsWith('.kt')),
  join(repoRoot, 'android-native', 'app', 'build.gradle.kts'),
].filter(existsSync);
const appUrls = new Map();
for (const file of appFiles) {
  const text = readFileSync(file, 'utf8');
  const where = relative(repoRoot, file).split(sep).join('/');
  for (const m of text.matchAll(/https?:\/\/[A-Za-z0-9._~:/?#@!$&*+,;=%-]+/g)) {
    const url = m[0].replace(/[.,;:)]+$/, '');
    // Namespaces, string templates, scam-shield test samples, API endpoints and emulator hosts are not links.
    if (/schemas\.android\.com|apache\.org|w3\.org|\$|googleapis\.com|firebaseio\.com|example\.|evil\.com|bank\.com|localhost/.test(url)) continue;
    if (url === 'http://' || url === 'https://') continue;
    if (!appUrls.has(url)) appUrls.set(url, where);
  }
}
for (const [url, where] of appUrls) {
  let u;
  try { u = new URL(url); } catch { note('app', where, url, 'malformed URL'); continue; }
  if (SITE_HOSTS.has(u.hostname)) checkSiteUrl(u.pathname, u.hash.slice(1), where, 'app');
  else if (!externalUrls.has(url)) externalUrls.set(url, where);
}

// ---------- External ----------
const externalFailures = [];
const externalSkipped = [];
async function probe(url) {
  const opts = { redirect: 'follow', signal: AbortSignal.timeout(15000), headers: { 'user-agent': 'samvad-link-check/1.0' } };
  try {
    let res = await fetch(url, { ...opts, method: 'HEAD' });
    if (res.status >= 400) res = await fetch(url, { ...opts, method: 'GET' });
    return res.status;
  } catch (e) {
    return `error: ${e.cause?.code ?? e.name}`;
  }
}
if (checkExternal) {
  for (const [url, where] of externalUrls) {
    const status = await probe(url);
    if (typeof status === 'number' && status >= 200 && status < 400) continue;
    // 401/403/429 mean the host answered but blocks scripts or needs a login: cannot verify automatically.
    if (status === 401 || status === 403 || status === 429) externalSkipped.push({ url, where, status });
    else externalFailures.push({ url, where, status });
  }
}

// ---------- Report ----------
console.log(`Checked ${htmlFiles.length} built pages, ${redirects.size} redirects, ${mdFiles.length} markdown files, ${appUrls.size} app URLs.`);
console.log(`External URLs found: ${externalUrls.size}${checkExternal ? '' : ' (not requested; run with --external)'}`);
if (broken.length) {
  console.log(`\nBROKEN INTERNAL LINKS (${broken.length}):`);
  for (const b of broken) console.log(`  [${b.kind}] ${b.where}\n      ${b.target}  ->  ${b.why}`);
}
if (externalFailures.length) {
  console.log(`\nEXTERNAL URLS THAT FAILED (${externalFailures.length}):`);
  for (const f of externalFailures) console.log(`  ${f.url}  (${f.status})  in ${f.where}`);
}
if (externalSkipped.length) {
  console.log(`\nEXTERNAL URLS THAT COULD NOT BE VERIFIED (${externalSkipped.length}, host blocks scripts or needs login):`);
  for (const f of externalSkipped) console.log(`  ${f.url}  (${f.status})  in ${f.where}`);
}
if (!broken.length && !externalFailures.length) console.log('\nNo broken links found.');
process.exit(broken.length || (strict && externalFailures.length) ? 1 : 0);
